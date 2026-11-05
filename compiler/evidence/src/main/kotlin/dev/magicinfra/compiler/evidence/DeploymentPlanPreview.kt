package dev.magicinfra.compiler.evidence

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.json.JsonMapper
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.dialect.Dialects

internal object DeploymentPlanPreview {
    private val environmentId = Regex("[a-z][a-z0-9-]{0,62}")
    private val mapper = JsonMapper.builder().build()
    private val schema by lazy {
        val registry = SchemaRegistry.withDialect(Dialects.getDraft202012())
        checkNotNull(DeploymentPlanPreview::class.java.getResourceAsStream("/plan.v1alpha1.schema.json")).use {
            registry.getSchema(it)
        }
    }

    fun plan(model: JsonNode, evidence: JsonNode, environment: String): JsonNode {
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
                throw EvidenceRejected("MI_COMPOSE_PORT_CONFLICT",
                    "Set distinct ports for services on the Compose host. Port $port is already used in this model.", 4)
            }
        }
        val resources = model.path("resources")
        val managed = resources.any { it.path("management").asText() == "managed" }
        val operations = resources.map { resource ->
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
            add(prerequisite("compose-v2", "Provide a Docker engine with Compose 2.30 or later."))
            for (service in model.path("services")) {
                val id = service.path("id").asText()
                val image = service.path("build").path("imageRef").asText()
                add(prerequisite("image-$id", "Make image $image available for service $id."))
            }
            for (resource in resources) {
                val id = resource.path("id").asText()
                val connection = resource.path("connection")
                val secretRef = connection.path("secretRef").asText()
                add(prerequisite("credentials-$id", "Provide credentials reference $secretRef for resource $id."))
                if (connection.has("endpointRef")) {
                    val endpointRef = connection.path("endpointRef").asText()
                    add(prerequisite("endpoint-$id", "Provide external JDBC endpoint reference $endpointRef for resource $id."))
                }
            }
        }
        val document = mapper.valueToTree<JsonNode>(linkedMapOf(
            "schemaVersion" to "plan/v1alpha1",
            "applicationId" to model.path("application").path("id").asText(),
            "target" to mapOf("backend" to "docker-compose", "environment" to environment),
            "mode" to "compile-preview",
            "operations" to operations,
            "prerequisites" to prerequisites,
            "artifacts" to listOf(mapOf("path" to "compose.yaml", "kind" to "compose-manifest")),
            "diagnostics" to diagnostics,
            "limitations" to buildList {
                add("Compilation preview. No deployed state was read.")
                if (managed) add("Compose does not enforce the declared storage size.")
                add("Single-host Compose cannot provide host failover.")
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
