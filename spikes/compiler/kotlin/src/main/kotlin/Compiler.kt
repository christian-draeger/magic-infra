import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

private val gson = GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create()
private val name = Regex("^[a-z][a-z0-9-]{0,38}[a-z0-9]$|^[a-z]$")
private val endpoint = Regex("^[A-Z_][A-Z0-9_]*$")
private val size = Regex("^[1-9][0-9]*Gi$")

private fun JsonObject.text(key: String): String = get(key)?.asString.orEmpty()
private fun Map<*, *>.text(key: String): String = this[key] as? String ?: ""

private data class Service(val image: String, val port: Int, val module: String)
private data class Postgres(
    val id: String,
    val management: String,
    val database: String,
    val secret: String,
    val storage: String,
    val endpointRef: String,
) {
    val managed: Boolean get() = management == "managed"
}

private data class Input(
    val app: String,
    val adapter: String,
    val services: Map<String, Service>,
    val database: Postgres,
    val findings: List<String>,
    val diagnostics: Any,
)

fun main(args: Array<String>) {
    try {
        require(args.size == 4 && args[0] in setOf("docker-compose", "kubernetes")) { "E_USAGE" }
        val input = readInput(Path.of(args[1]), Path.of(args[2]))
        val files = compile(args[0], input)
        val destination = Path.of(args[3])
        try {
            Files.createDirectory(destination)
            for ((file, document) in files) {
                Files.writeString(destination.resolve(file), gson.toJson(document) + "\n")
            }
        } catch (_: IOException) {
            error("E_OUTPUT")
        }
    } catch (error: Exception) {
        System.err.println(error.message?.takeIf { it.matches(Regex("^E_[A-Z_]+$")) } ?: "E_INPUT")
        exitProcess(1)
    }
}

private fun readInput(evidence: Path, declaration: Path): Input {
    val observed = Files.newBufferedReader(evidence).use { JsonParser.parseReader(it).asJsonObject }
    val yaml = Yaml(SafeConstructor(LoaderOptions().apply { isAllowDuplicateKeys = false }))
    val declared = Files.newBufferedReader(declaration).use { yaml.load<Map<*, *>>(it) }
    require(observed.text("schemaVersion") == "evidence/v1alpha1" &&
        declared.text("schemaVersion") == "infra/v1alpha1") { "E_VERSION" }
    val db = declared["postgres"] as? Map<*, *> ?: error("E_OWNERSHIP")
    val pg = Postgres(db.text("id"), db.text("management"), db.text("database"), db.text("secretRef"),
        db.text("storageSize"), db.text("endpointRef"))
    require(pg.management in setOf("managed", "external")) { "E_OWNERSHIP" }
    require(name.matches(pg.secret)) { "E_SECRET_REF" }
    require(pg.managed || endpoint.matches(pg.endpointRef)) { "E_ENDPOINT_REF" }
    require(declared["requireHostFailover"] != true) { "E_CAPABILITY" }

    val app = declared.text("applicationId")
    val adapter = observed.getAsJsonObject("adapter").text("id")
    val declaredServices = declared["services"] as? Map<*, *> ?: error("E_DECLARATION")
    val observedServices = observed.getAsJsonArray("services")
    require(name.matches(app) && observed.getAsJsonObject("application").text("id") == app && adapter.isNotEmpty() &&
        name.matches(pg.id) && name.matches(pg.database) && (!pg.managed || size.matches(pg.storage)) &&
        observedServices.size() > 0 && observedServices.size() == declaredServices.size) { "E_DECLARATION" }
    val services = sortedMapOf<String, Service>()
    for (entry in observedServices) {
        val source = entry.asJsonObject
        val id = source.text("id")
        val module = source.text("module")
        val runtime = declaredServices[id] as? Map<*, *> ?: error("E_DECLARATION")
        val image = runtime.text("imageRef")
        val port = runtime["port"] as? Int ?: error("E_DECLARATION")
        require(name.matches(id) && id != pg.id && id !in services && module.isNotEmpty() &&
            image.isNotEmpty() && port in 1..65535) { "E_DECLARATION" }
        services[id] = Service(image, port, module)
    }

    val seen = mutableSetOf<String>()
    val relationalServices = mutableSetOf<String>()
    val findings = mutableListOf<String>()
    for (entry in observed.getAsJsonArray("findings")) {
        val finding = entry.asJsonObject
        val id = finding.text("id")
        val service = finding.text("serviceId")
        require(id.isNotEmpty() && seen.add(id) && service in services) { "E_EVIDENCE" }
        if (finding.text("capability") == "database.relational") {
            require(finding.text("implementation") in setOf("", "postgresql")) { "E_CONFLICT" }
            relationalServices += service
            findings += id
        }
    }
    require(relationalServices.size == services.size) { "E_EVIDENCE" }
    return Input(app, adapter, services, pg, findings.sorted(), observed["diagnostics"] ?: emptyList<Any>())
}

private fun compile(target: String, input: Input): Map<String, Any> {
    val db = input.database
    val connection = mutableMapOf("secretRef" to db.secret)
    val settings = mutableMapOf<String, Any>("database" to db.database)
    if (db.managed) {
        settings["storage"] = mapOf("size" to db.storage)
    } else {
        connection["endpointRef"] = db.endpointRef
    }
    val model = mapOf(
        "schemaVersion" to "application/v1alpha1",
        "application" to mapOf("id" to input.app),
        "services" to input.services.map { (id, s) ->
            mapOf("id" to id, "source" to mapOf("adapter" to input.adapter, "module" to s.module),
                "build" to mapOf("strategy" to "prebuilt", "imageRef" to s.image), "runtime" to mapOf("port" to s.port))
        },
        "resources" to listOf(mapOf("id" to db.id, "kind" to "postgresql", "management" to db.management,
            "deletionPolicy" to "retain", "settings" to settings, "connection" to connection)),
        "bindings" to input.services.keys.map { id ->
            mapOf("serviceId" to id, "resourceId" to db.id, "contract" to "spring.datasource")
        },
        "provenance" to listOf(mapOf("resourceId" to db.id, "findingIds" to input.findings, "declarationRef" to "infra.yaml#/postgres")),
    )
    val operations = listOf(operation(db.id, if (db.managed) "create" else "reference",
        if (db.managed) "moderate" else "low", "Resolve PostgreSQL ownership.")) +
        input.services.keys.map { operation(it, "bind", "low", "Wire the application to PostgreSQL.") }
    val prereqs = mutableListOf(prerequisite("oci-image", "Provide the prebuilt application image."))
    val limitations = mutableListOf("Compilation preview only.")
    val isCompose = target == "docker-compose"
    val path = if (isCompose) "compose.yaml" else "kubernetes.yaml"
    if (isCompose) {
        prereqs += prerequisite("credentials", "Provide the referenced local credentials env file.")
        if (!db.managed) {
            prereqs += prerequisite("endpoint", "Provide the external JDBC URL through the referenced environment variable.")
        }
        limitations += "Single-host Compose cannot provide host failover."
    } else {
        prereqs += prerequisite("credentials", "Provide the referenced Kubernetes Secret.")
        if (db.managed) {
            prereqs += prerequisite("cloudnativepg", "Install CloudNativePG and configure compatible storage.")
        }
    }
    val plan = mapOf(
        "schemaVersion" to "plan/v1alpha1", "applicationId" to input.app,
        "target" to mapOf("backend" to target, "environment" to "dev"), "mode" to "compile-preview",
        "operations" to operations, "prerequisites" to prereqs,
        "artifacts" to listOf(mapOf("path" to path, "kind" to if (isCompose) "compose-manifest" else "kubernetes-manifest")),
        "diagnostics" to input.diagnostics, "limitations" to limitations,
    )
    return mapOf("application.json" to model, "plan.json" to plan, path to if (isCompose) compose(input) else kubernetes(input))
}

private fun operation(id: String, action: String, risk: String, description: String) =
    mapOf("id" to "$action-$id", "resourceId" to id, "action" to action, "risk" to risk,
        "description" to description, "requiresApproval" to false)

private fun prerequisite(id: String, description: String) =
    mapOf("id" to id, "description" to description, "required" to true)

private fun jdbc(host: String, database: String) = "jdbc:postgresql://$host:5432/$database"

private fun compose(input: Input): Map<String, Any> {
    val db = input.database
    val credentials = listOf("./secrets/${db.secret}.env")
    val services = mutableMapOf<String, Any>()
    val result = mutableMapOf<String, Any>("name" to input.app, "services" to services)
    val url = if (db.managed) {
        services[db.id] = mapOf("image" to "postgres:16", "env_file" to credentials,
            "environment" to mapOf("POSTGRES_DB" to db.database), "volumes" to listOf("${db.id}:/var/lib/postgresql/data"))
        result["volumes"] = mapOf(db.id to emptyMap<String, String>())
        jdbc(db.id, db.database)
    } else {
        "\${${db.endpointRef}:?set external JDBC URL}"
    }
    for ((id, s) in input.services) {
        services[id] = mapOf("image" to s.image, "env_file" to credentials, "expose" to listOf(s.port),
            "environment" to mapOf("SPRING_DATASOURCE_URL" to url))
    }
    return result
}

private fun kubernetes(input: Input): Map<String, Any> {
    val db = input.database
    fun secretEnv(name: String, key: String) = mapOf("name" to name,
        "valueFrom" to mapOf("secretKeyRef" to mapOf("name" to db.secret, "key" to key)))

    val items = mutableListOf<Map<String, Any>>()
    if (db.managed) {
        items += mapOf("apiVersion" to "postgresql.cnpg.io/v1", "kind" to "Cluster",
            "metadata" to mapOf("name" to db.id), "spec" to mapOf("instances" to 1,
                "storage" to mapOf("size" to db.storage), "bootstrap" to mapOf("initdb" to mapOf(
                    "database" to db.database, "owner" to "app", "secret" to mapOf("name" to db.secret)))))
    }
    for ((id, s) in input.services) {
        val labels = mapOf("app" to id)
        val url = if (db.managed) mapOf("name" to "SPRING_DATASOURCE_URL", "value" to jdbc("${db.id}-rw", db.database))
            else secretEnv("SPRING_DATASOURCE_URL", db.endpointRef)
        val container = mapOf("name" to id, "image" to s.image, "ports" to listOf(mapOf("containerPort" to s.port)),
            "env" to listOf(url, secretEnv("SPRING_DATASOURCE_USERNAME", "username"), secretEnv("SPRING_DATASOURCE_PASSWORD", "password")))
        items += mapOf("apiVersion" to "apps/v1", "kind" to "Deployment", "metadata" to mapOf("name" to id),
            "spec" to mapOf("replicas" to 1, "selector" to mapOf("matchLabels" to labels),
                "template" to mapOf("metadata" to mapOf("labels" to labels), "spec" to mapOf("containers" to listOf(container)))))
        items += mapOf("apiVersion" to "v1", "kind" to "Service", "metadata" to mapOf("name" to id),
            "spec" to mapOf("selector" to labels, "ports" to listOf(mapOf("port" to s.port, "targetPort" to s.port))))
    }
    return mapOf("apiVersion" to "v1", "kind" to "List", "items" to items)
}
