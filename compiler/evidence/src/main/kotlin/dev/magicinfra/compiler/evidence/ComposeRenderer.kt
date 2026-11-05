package dev.magicinfra.compiler.evidence

import com.fasterxml.jackson.databind.JsonNode
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml

internal object ComposeRenderer {
    private const val POSTGRES = "postgres:16@sha256:933463a6c8eba2b9f5cd414138738cd8d5519cd2c471214c222652f547a66a32"

    fun render(model: JsonNode, plan: JsonNode): String {
        val app = model.path("application").path("id").asText()
        val environment = plan.path("target").path("environment").asText()
        val resources = model.path("resources").associateBy { it.path("id").asText() }
        val bindings = model.path("bindings").associateBy { it.path("serviceId").asText() }
        val services = linkedMapOf<String, Any>()
        val volumes = linkedMapOf<String, Any>()
        for (service in model.path("services")) {
            val id = service.path("id").asText()
            val port = service.path("runtime").path("port").asInt()
            val entry = linkedMapOf<String, Any>(
                "image" to service.path("build").path("imageRef").asText(),
                "ports" to listOf("127.0.0.1:$port:$port"),
            )
            val resourceId = bindings[id]?.path("resourceId")?.asText()
            if (resourceId != null) {
                val resource = resources.getValue(resourceId)
                val managed = resource.path("management").asText() == "managed"
                val connection = resource.path("connection")
                val database = resource.path("settings").path("database").asText()
                val endpoint = connection.path("endpointRef").asText()
                val jdbcUrl = if (managed) "jdbc:postgresql://$resourceId:5432/$database"
                    else "\${$endpoint:?Set $endpoint}"
                entry["env_file"] = credentialsFile(connection.path("secretRef").asText())
                entry["environment"] = mapOf("SPRING_DATASOURCE_URL" to jdbcUrl)
                if (managed) entry["depends_on"] = mapOf(resourceId to mapOf("condition" to "service_healthy"))
            }
            services[id] = entry
        }

        for ((id, resource) in resources) {
            if (resource.path("management").asText() != "managed") continue
            val database = resource.path("settings").path("database").asText()
            val secretRef = resource.path("connection").path("secretRef").asText()
            val volume = "$id-data"
            volumes[volume] = emptyMap<String, Any>()
            services[id] = mapOf(
                "image" to POSTGRES,
                "env_file" to credentialsFile(secretRef),
                "environment" to mapOf("POSTGRES_DB" to database),
                "volumes" to listOf("$volume:/var/lib/postgresql/data"),
                "healthcheck" to mapOf(
                    "test" to listOf("CMD-SHELL", "pg_isready -h 127.0.0.1 -U \"\$\$POSTGRES_USER\" -d \"\$\$POSTGRES_DB\""),
                    "interval" to "2s",
                    "timeout" to "5s",
                    "retries" to 20,
                    "start_period" to "5s",
                ),
            )
        }

        val document = linkedMapOf<String, Any>("name" to "$app-$environment", "services" to services)
        if (volumes.isNotEmpty()) document["volumes"] = volumes
        val options = DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            lineBreak = DumperOptions.LineBreak.UNIX
            splitLines = false
        }
        return Yaml(options).dump(document)
    }

    private fun credentialsFile(secretRef: String): List<Map<String, String>> =
        listOf(mapOf("path" to "./secrets/$secretRef.env", "format" to "raw"))
}
