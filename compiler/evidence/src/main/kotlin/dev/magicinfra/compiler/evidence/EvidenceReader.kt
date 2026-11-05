package dev.magicinfra.compiler.evidence

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.json.JsonMapper
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.dialect.Dialects
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException

class EvidenceRejected(val code: String, message: String, val status: Int = 2) :
    IllegalArgumentException(message)

object EvidenceReader {
    private const val VERSION = "evidence/v1alpha1"
    private const val MAX_EVIDENCE_BYTES = 4 * 1024 * 1024
    private val mapper = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build()
    private val schema by lazy {
        val registry = SchemaRegistry.withDialect(Dialects.getDraft202012())
        checkNotNull(EvidenceReader::class.java.getResourceAsStream("/evidence.v1alpha1.schema.json")).use {
            registry.getSchema(it)
        }
    }

    fun read(input: InputStream): JsonNode {
        val bytes = input.readNBytes(MAX_EVIDENCE_BYTES + 1)
        if (bytes.size > MAX_EVIDENCE_BYTES) {
            throw EvidenceRejected("MI_EVIDENCE_TOO_LARGE", "Keep the Evidence document at or below 4 MiB.")
        }
        val document = try {
            mapper.readTree(Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString())
        } catch (_: JsonProcessingException) {
            invalidJson()
        } catch (_: CharacterCodingException) {
            invalidJson()
        }
        if (document == null || !document.isObject) invalidJson()

        val version = document.path("schemaVersion")
        if (!version.isTextual) {
            throw EvidenceRejected("MI_EVIDENCE_SCHEMA", "Set /schemaVersion to $VERSION.")
        }
        if (version.asText() != VERSION) {
            throw EvidenceRejected("MI_EVIDENCE_VERSION", "Use an adapter that emits $VERSION.", 3)
        }
        val error = schema.validate(document).minWithOrNull(compareBy({ it.instanceLocation.toString() }, { it.keyword }))
        if (error != null) {
            val path = error.instanceLocation.toString().ifEmpty { "/" }
            throw EvidenceRejected("MI_EVIDENCE_SCHEMA", "Correct $path to satisfy the Evidence ${error.keyword} rule.")
        }

        val services = uniqueIds(document.path("services"), "/services")
        val findings = document.path("findings")
        uniqueIds(findings, "/findings")
        findings.forEachIndexed { index, finding ->
            if (finding.path("serviceId").asText() !in services) {
                throw EvidenceRejected("MI_EVIDENCE_REFERENCE", "Set /findings/$index/serviceId to an existing service ID.")
            }
        }
        return document
    }

    private fun uniqueIds(nodes: JsonNode, path: String): Set<String> {
        val ids = mutableSetOf<String>()
        nodes.forEachIndexed { index, node ->
            if (!ids.add(node.path("id").asText())) {
                throw EvidenceRejected("MI_EVIDENCE_REFERENCE", "Use a unique ID at $path/$index/id.")
            }
        }
        return ids
    }

    private fun invalidJson(): Nothing =
        throw EvidenceRejected("MI_EVIDENCE_INVALID_JSON", "Provide exactly one UTF-8 JSON object.")
}
