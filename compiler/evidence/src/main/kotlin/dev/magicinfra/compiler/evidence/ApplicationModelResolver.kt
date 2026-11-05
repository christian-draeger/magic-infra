package dev.magicinfra.compiler.evidence

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.core.util.DefaultIndenter
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.dialect.Dialects

internal object ApplicationModelResolver {
    private val mapper = JsonMapper.builder().build()
    private val writer = mapper.writer(DefaultPrettyPrinter().withObjectIndenter(DefaultIndenter("  ", "\n")))
    private val schema by lazy {
        val registry = SchemaRegistry.withDialect(Dialects.getDraft202012())
        checkNotNull(ApplicationModelResolver::class.java.getResourceAsStream("/application.v1alpha1.schema.json")).use {
            registry.getSchema(it)
        }
    }

    fun resolve(evidence: JsonNode, declaration: JsonNode): String {
        val id = declaration.path("applicationId").asText()
        if (id != evidence.path("application").path("id").asText()) {
            throw EvidenceRejected("MI_APPLICATION_ID_MISMATCH", "Set /applicationId to the analyzed application ID.")
        }
        val declaredServices = declaration.path("services")
        val evidenceServices = evidence.path("services").associateBy { it.path("id").asText() }
        val serviceIds = declaredServices.fieldNames().asSequence().toList().sorted()
        if (serviceIds.toSet() != evidenceServices.keys) {
            throw EvidenceRejected("MI_APPLICATION_SERVICE_MISMATCH", "Declare exactly the service IDs present in Evidence under /services.")
        }
        val adapterId = evidence.path("adapter").path("id").asText()
        if (adapterId.length > 128) {
            throw EvidenceRejected("MI_APPLICATION_PROVENANCE_LENGTH", "Keep the Evidence /adapter/id at or below 128 characters.")
        }
        for (serviceId in serviceIds) {
            if (evidenceServices.getValue(serviceId).path("module").asText().length > 240) {
                throw EvidenceRejected("MI_APPLICATION_PROVENANCE_LENGTH", "Keep the Evidence module for service $serviceId at or below 240 characters.")
            }
        }
        val resources = declaration.path("resources")
        val bindings = declaration.path("bindings").sortedBy { it.path("serviceId").asText() }
        val byResource = bindings.groupBy { it.path("resourceId").asText() }
        val resourceIds = resources.fieldNames().asSequence().toList().sorted()
        for (resourceId in resourceIds) {
            if (resourceId !in byResource) {
                throw EvidenceRejected("MI_APPLICATION_ORPHAN_RESOURCE", "Bind /resources/$resourceId to at least one service.")
            }
        }
        if (bindings.isNotEmpty() && evidence.path("application").path("framework").asText() != "spring-boot") {
            throw EvidenceRejected("MI_APPLICATION_BINDING_UNSUPPORTED", "The initial PostgreSQL binding requires Spring Boot Evidence.")
        }
        val intents = bindings.map { binding ->
            val resource = resources.path(binding.path("resourceId").asText())
            val vendor = checkNotNull(DatabaseVendor.named(resource.path("kind").asText()))
            val management = if (resource.path("management").asText() == "external") DatabaseManagement.EXTERNAL
            else DatabaseManagement.MANAGED
            DeclaredDatabase(binding.path("serviceId").asText(), vendor, management)
        }
        val suggestions = suggestRelational(evidence, intents).associateBy { it.serviceId }
        for (suggestion in suggestions.values) {
            if (suggestion.resolution == Resolution.CONFLICTED) {
                throw EvidenceRejected("MI_APPLICATION_VENDOR_CONFLICT",
                    "Service ${suggestion.serviceId}. ${suggestion.explanation}", 4)
            }
            if (suggestion.resolution == Resolution.SUGGESTED) {
                throw EvidenceRejected("MI_APPLICATION_BINDING_REQUIRED",
                    "Declare a database and a binding under /bindings for service ${suggestion.serviceId}.", 4)
            }
        }

        val model = linkedMapOf(
            "schemaVersion" to "application/v1alpha1",
            "application" to mapOf("id" to id),
            "services" to serviceIds.map { serviceId ->
                val service = declaredServices.path(serviceId)
                val runtime = linkedMapOf<String, Any>("port" to service.path("port").asInt())
                if (service.has("healthPath")) runtime["healthPath"] = service.path("healthPath").asText()
                mapOf(
                    "id" to serviceId,
                    "source" to mapOf("adapter" to adapterId, "module" to evidenceServices.getValue(serviceId).path("module").asText()),
                    "build" to mapOf("strategy" to "prebuilt", "imageRef" to service.path("imageRef").asText()),
                    "runtime" to runtime,
                )
            },
            "resources" to resourceIds.map { resourceId -> normalizeResource(resourceId, resources.path(resourceId)) },
            "bindings" to bindings.map { binding ->
                mapOf("serviceId" to binding.path("serviceId").asText(),
                    "resourceId" to binding.path("resourceId").asText(), "contract" to "spring.datasource")
            },
            "provenance" to resourceIds.map { resourceId ->
                val contributingFindings = byResource.getValue(resourceId).flatMap { binding ->
                    suggestions.getValue(binding.path("serviceId").asText()).findingIds
                }.distinct().sorted()
                mapOf("resourceId" to resourceId, "findingIds" to contributingFindings,
                    "declarationRef" to "#/resources/$resourceId")
            },
        )
        val document = mapper.valueToTree<JsonNode>(model)
        if (schema.validate(document).isNotEmpty()) {
            throw EvidenceRejected("MI_APPLICATION_SCHEMA", "The resolver produced an invalid Application Model.", 1)
        }
        return writer.writeValueAsString(document) + "\n"
    }

    private fun normalizeResource(id: String, resource: JsonNode): Map<String, Any> {
        val settings = linkedMapOf<String, Any>("database" to resource.path("database").asText())
        if (resource.has("storageSize")) settings["storage"] = mapOf("size" to resource.path("storageSize").asText())
        val connection = linkedMapOf("secretRef" to resource.path("secretRef").asText())
        if (resource.has("endpointRef")) connection["endpointRef"] = resource.path("endpointRef").asText()
        return mapOf(
            "id" to id,
            "kind" to resource.path("kind").asText(),
            "management" to resource.path("management").asText(),
            "deletionPolicy" to resource.path("deletionPolicy").asText(),
            "settings" to settings,
            "connection" to connection,
        )
    }
}
