package dev.magicinfra.compiler.evidence

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.json.JsonMapper
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.dialect.Dialects

internal enum class DeploymentTarget(
    val id: String,
    val label: String,
    val diagnosticPrefix: String,
    val artifactPath: String,
    val artifactKind: String,
) {
    COMPOSE("docker-compose", "Compose", "COMPOSE", "compose.yaml", "compose-manifest"),
    NIXOS("nixos", "NixOS", "NIXOS", "magic-infra.nix", "nixos-module"),
}

internal object DeploymentPlanPreview {
    private val environmentId = Regex("[a-z][a-z0-9-]{0,62}")
    private val mapper = JsonMapper.builder().build()
    private val schema by lazy {
        val registry = SchemaRegistry.withDialect(Dialects.getDraft202012())
        checkNotNull(DeploymentPlanPreview::class.java.getResourceAsStream("/plan.v1alpha1.schema.json")).use {
            registry.getSchema(it)
        }
    }

    fun plan(model: JsonNode, evidence: JsonNode, environment: String, target: DeploymentTarget): JsonNode {
        if (!environmentId.matches(environment)) {
            throw EvidenceRejected("MI_PLAN_ENVIRONMENT", "Use a lowercase environment ID such as dev.")
        }
        val diagnostics = evidence.path("diagnostics")
        if (diagnostics.any { it.path("severity").asText() == "error" }) {
            throw EvidenceRejected("MI_PLAN_EVIDENCE_ERROR",
                "Inspect and resolve adapter errors with evidence-check analyze before planning.", 4)
        }
        val ports = mutableSetOf<Int>()
        for (service in model.path("services")) {
            val port = service.path("runtime").path("port").asInt()
            if (!ports.add(port)) {
                throw EvidenceRejected("MI_${target.diagnosticPrefix}_PORT_CONFLICT",
                    "Set distinct ports for services on the ${target.label} host. Port $port is already used in this model.", 4)
            }
        }
        val resources = model.path("resources")
        val managed = resources.any { it.path("management").asText() == "managed" }
        if (target == DeploymentTarget.NIXOS && managed) {
            throw EvidenceRejected("MI_NIXOS_RESOURCE_UNSUPPORTED",
                "NixOS currently supports external PostgreSQL. Select Compose for managed PostgreSQL.", 4)
        }
        val app = model.path("application").path("id").asText()
        val workloads = if (target == DeploymentTarget.NIXOS) model.path("services").map { service ->
            val id = service.path("id").asText()
            operation("create", id, "low", "Configure the Podman container for service $id when activated.")
        } else emptyList()
        val operations = workloads + resources.map { resource ->
            val id = resource.path("id").asText()
            if (resource.path("management").asText() == "managed") {
                operation("create", id, "moderate", "Create PostgreSQL with retained storage when deployed.")
            } else {
                operation("reference", id, "low", "Use the existing PostgreSQL endpoint reference.")
            }
        } + model.path("bindings").map { binding ->
            val serviceId = binding.path("serviceId").asText()
            val resourceId = binding.path("resourceId").asText()
            operation("bind", serviceId, "low", "Bind service $serviceId to PostgreSQL resource $resourceId.")
        }
        val prerequisites = buildList {
            add(when (target) {
                DeploymentTarget.COMPOSE -> prerequisite("compose-v2", "Provide a Docker engine with Compose 2.30 or later.")
                DeploymentTarget.NIXOS -> prerequisite("nixos-podman", "Import the module on a NixOS host with Podman as its OCI backend.")
            })
            for (service in model.path("services")) {
                val id = service.path("id").asText()
                val image = service.path("build").path("imageRef").asText()
                add(prerequisite("image-$id", "Make image $image available for service $id."))
            }
            for (resource in resources) {
                val id = resource.path("id").asText()
                val connection = resource.path("connection")
                val secretRef = connection.path("secretRef").asText()
                val credentials = if (target == DeploymentTarget.NIXOS) {
                    "Supply SPRING_DATASOURCE_USERNAME and SPRING_DATASOURCE_PASSWORD in ${nixosCredentialsPath(app, environment, secretRef)} at runtime."
                } else "Provide credentials reference $secretRef for resource $id."
                add(prerequisite("credentials-$id", credentials))
                if (connection.has("endpointRef")) {
                    val endpointRef = connection.path("endpointRef").asText()
                    val endpoint = if (target == DeploymentTarget.NIXOS) {
                        "Supply SPRING_DATASOURCE_URL in ${nixosEndpointPath(app, environment, endpointRef)} at runtime."
                    } else "Provide external JDBC endpoint reference $endpointRef for resource $id."
                    add(prerequisite("endpoint-$id", endpoint))
                }
            }
        }
        val document = mapper.valueToTree<JsonNode>(linkedMapOf(
            "schemaVersion" to "plan/v1alpha1",
            "applicationId" to app,
            "target" to mapOf("backend" to target.id, "environment" to environment),
            "mode" to "compile-preview",
            "operations" to operations,
            "prerequisites" to prerequisites,
            "artifacts" to listOf(mapOf("path" to target.artifactPath, "kind" to target.artifactKind)),
            "diagnostics" to diagnostics,
            "limitations" to buildList {
                add("Compilation preview. No deployed state was read.")
                if (managed) add("Compose does not enforce the declared storage size.")
                if (target == DeploymentTarget.NIXOS) add("Application health requires an external probe.")
                add("Single-host ${target.label} cannot provide host failover.")
            },
        ))
        if (schema.validate(document).isNotEmpty()) {
            throw EvidenceRejected("MI_PLAN_SCHEMA", "The planner produced an invalid Deployment Plan.", 1)
        }
        return document
    }

    private fun operation(action: String, id: String, risk: String, description: String): Map<String, Any> =
        mapOf("id" to "$action-$id", "resourceId" to id, "action" to action, "risk" to risk,
            "description" to description, "requiresApproval" to false)

    private fun prerequisite(id: String, description: String): Map<String, Any> =
        mapOf("id" to id, "description" to description, "required" to true)
}
