package dev.magicinfra.compiler.evidence

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.json.JsonMapper
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.dialect.Dialects
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.error.MarkedYAMLException
import org.yaml.snakeyaml.error.YAMLException
import org.yaml.snakeyaml.events.AliasEvent
import org.yaml.snakeyaml.events.CollectionStartEvent
import org.yaml.snakeyaml.events.NodeEvent
import org.yaml.snakeyaml.events.ScalarEvent
import org.yaml.snakeyaml.nodes.Tag
import org.yaml.snakeyaml.representer.Representer
import org.yaml.snakeyaml.resolver.Resolver
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.util.regex.Pattern

internal class InfraRejected(val code: String, message: String, val status: Int = 2) :
    IllegalArgumentException(message)

internal object InfraDeclarationReader {
    private const val VERSION = "infra/v1alpha2"
    private const val MAX_BYTES = 256 * 1024
    private val mapper = JsonMapper.builder().build()
    private val schemaFields = setOf("schemaVersion", "applicationId", "services", "resources", "bindings", "imageRef",
        "port", "kind", "management", "deletionPolicy", "database", "secretRef", "storageSize", "endpointRef",
        "serviceId", "resourceId", "password")
    private val displayControl = Regex("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]")
    private val schema by lazy {
        val registry = SchemaRegistry.withDialect(Dialects.getDraft202012())
        checkNotNull(InfraDeclarationReader::class.java.getResourceAsStream("/infra.v1alpha2.schema.json")).use {
            registry.getSchema(it)
        }
    }

    fun read(input: InputStream): JsonNode {
        val bytes = input.readNBytes(MAX_BYTES + 1)
        if (bytes.size > MAX_BYTES) {
            throw InfraRejected("MI_INFRA_TOO_LARGE", "Keep the declaration at or below 256 KiB.")
        }
        val source = try {
            Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            invalidYaml()
        }
        val options = LoaderOptions().apply {
            isAllowDuplicateKeys = false
            maxAliasesForCollections = 0
            nestingDepthLimit = 32
            codePointLimit = MAX_BYTES
        }
        val dumpOptions = DumperOptions()
        val yaml = Yaml(SafeConstructor(options), Representer(dumpOptions), dumpOptions, options, DeclarationScalars())
        val tree = try {
            for (event in yaml.parse(source.reader())) {
                if (event is AliasEvent || (event is NodeEvent && event.anchor != null) ||
                    (event is ScalarEvent && (event.tag != null || event.value == "<<")) ||
                    (event is CollectionStartEvent && event.tag != null)) {
                    throw InfraRejected("MI_INFRA_YAML_FEATURE",
                        "Use explicit values without YAML tags, anchors, aliases or merge keys.")
                }
            }
            yaml.load<Any?>(source)
        } catch (failure: MarkedYAMLException) {
            invalidYaml(failure.problemMark?.line?.plus(1))
        } catch (_: YAMLException) {
            invalidYaml()
        } catch (_: NumberFormatException) {
            invalidYaml()
        }
        if (tree !is Map<*, *> || !jsonCompatible(tree)) invalidYaml()

        val declaration = mapper.valueToTree<JsonNode>(tree)
        val version = declaration.path("schemaVersion")
        if (!version.isTextual) {
            throw InfraRejected("MI_INFRA_SCHEMA", "Set /schemaVersion to $VERSION.")
        }
        if (version.asText() != VERSION) {
            throw InfraRejected("MI_INFRA_VERSION", "Migrate the declaration to $VERSION.", 3)
        }
        val error = schema.validate(declaration).minWithOrNull(compareBy({ it.instanceLocation.toString() }, { it.keyword }))
        if (error != null) {
            val location = error.instanceLocation.toString()
            val field = error.property?.takeIf { it in schemaFields }
            val safeLocation = if (field == null && error.keyword == "additionalProperties" && error.property != null) {
                location.removeSuffix("/${error.property}")
            } else location
            val base = safeLocation.replace(displayControl, "_").take(200)
            val path = if (field == null || base.endsWith("/$field")) base else "${base.trimEnd('/')}/$field"
            throw InfraRejected("MI_INFRA_SCHEMA", "Correct ${path.ifEmpty { "/" }} to satisfy the ${error.keyword} rule.")
        }
        validateBindings(declaration)
        return declaration
    }

    private fun jsonCompatible(value: Any?): Boolean = when (value) {
        null, is String, is Boolean, is Number -> true
        is Map<*, *> -> value.all { (key, item) -> key is String && jsonCompatible(item) }
        is List<*> -> value.all(::jsonCompatible)
        else -> false
    }

    private fun validateBindings(declaration: JsonNode) {
        val services = declaration.path("services")
        val resources = declaration.path("resources")
        for (resourceId in resources.fieldNames()) {
            if (services.has(resourceId)) {
                throw InfraRejected("MI_INFRA_REFERENCE", "Use a different ID at /resources/$resourceId. A service already uses that ID.")
            }
        }
        val seen = mutableSetOf<String>()
        declaration.path("bindings").forEachIndexed { index, binding ->
            val serviceId = binding.path("serviceId").asText()
            if (!services.has(serviceId) || !seen.add(serviceId)) {
                throw InfraRejected("MI_INFRA_REFERENCE", "Set /bindings/$index/serviceId to an existing service with no other database binding.")
            }
            if (!resources.has(binding.path("resourceId").asText())) {
                throw InfraRejected("MI_INFRA_REFERENCE", "Set /bindings/$index/resourceId to a declared resource.")
            }
        }
    }

    private fun invalidYaml(line: Int? = null): Nothing {
        val location = line?.let { " at line $it" }.orEmpty()
        throw InfraRejected("MI_INFRA_YAML", "Provide one UTF-8 YAML mapping with unique string keys$location.")
    }
}

private class DeclarationScalars : Resolver() {
    override fun addImplicitResolvers() {
        // This DSL only needs plain decimal integers and strings.
        addImplicitResolver(Tag.INT, DECIMAL, "-0123456789")
    }

    private companion object {
        val DECIMAL: Pattern = Pattern.compile("-?(?:0|[1-9][0-9]{0,9})")
    }
}
