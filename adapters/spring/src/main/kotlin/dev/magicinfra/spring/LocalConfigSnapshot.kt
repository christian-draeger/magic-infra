package dev.magicinfra.spring

import org.springframework.boot.context.config.ConfigDataLocation
import org.springframework.boot.context.config.ConfigDataLocationResolver
import org.springframework.boot.context.config.ConfigDataLocationResolverContext
import org.springframework.boot.context.config.ConfigDataResource
import org.springframework.boot.origin.Origin
import org.springframework.core.io.AbstractResource
import org.springframework.core.io.Resource
import org.springframework.core.io.ResourceLoader
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.events.AliasEvent
import org.yaml.snakeyaml.reader.UnicodeReader
import java.io.FileNotFoundException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.Collections

internal const val CONFIG_PREFIX = "file:/__magic_infra_config__/"
private const val MAX_CONFIG_FILES = 128
private const val MAX_CONFIG_BYTES = 2 * 1024 * 1024
private val configDocument = Regex("[A-Za-z0-9_.-]+\\.(?:properties|yaml|yml)")

internal class ConfigUnavailable(val code: String, message: String, val origin: Origin? = null) :
    IllegalArgumentException(message)

internal class LocalConfigSnapshot private constructor(private val documents: Map<String, ByteArray>) : ResourceLoader {
    val isEmpty: Boolean get() = documents.isEmpty()

    override fun getClassLoader(): ClassLoader = configFactories

    override fun getResource(location: String): Resource {
        check(location.startsWith(CONFIG_PREFIX))
        val path = location.removePrefix(CONFIG_PREFIX)
        // The resolver can only access bytes already collected from the project.
        return LocalConfigResource(path, documents[path])
    }

    companion object {
        // Use only the built-in properties/YAML readers and our import guard.
        private val configFactories = object : ClassLoader(LocalConfigSnapshot::class.java.classLoader) {
            override fun getResources(name: String) = if (name == "META-INF/spring.factories") {
                Collections.enumeration(listOf(checkNotNull(parent.getResource("META-INF/magic-infra-config.factories"))))
            } else {
                super.getResources(name)
            }
        }

        fun read(project: Path, sourceRoots: List<String>): LocalConfigSnapshot {
            val documents = sortedMapOf<String, ByteArray>()
            var bytesLeft = MAX_CONFIG_BYTES
            for (sourceRoot in sourceRoots) {
                val root = project.resolve(sourceRoot)
                if (sourceRoot.split('/').runningFold(project) { path, segment -> path.resolve(segment) }
                        .any(Files::isSymbolicLink)) {
                    throw ConfigUnavailable("MI_SPRING_CONFIG_LINK", "A configuration root uses a symbolic link.")
                }
                if (!Files.isDirectory(root, NOFOLLOW_LINKS)) continue
                Files.walk(root).use { files ->
                    files.forEach { file ->
                        if (Files.isSymbolicLink(file)) {
                            throw ConfigUnavailable("MI_SPRING_CONFIG_LINK", "A configuration path uses a symbolic link.")
                        }
                        if (!configDocument.matches(file.fileName.toString())) return@forEach
                        val path = project.relativize(file).joinToString("/")
                        if (path in documents) return@forEach
                        if (!Files.isRegularFile(file, NOFOLLOW_LINKS)) return@forEach
                        if (documents.size >= MAX_CONFIG_FILES) tooLarge()
                        val contents = Files.newInputStream(file, NOFOLLOW_LINKS).use { it.readNBytes(bytesLeft + 1) }
                        bytesLeft -= contents.size
                        if (bytesLeft < 0) tooLarge()
                        documents[path] = contents
                    }
                }
            }
            return LocalConfigSnapshot(documents)
        }

        private fun tooLarge(): Nothing =
            throw ConfigUnavailable("MI_SPRING_CONFIG_LIMIT", "Keep local configuration within 128 files and 2 MiB.")
    }
}

internal class LocalConfigResource(val path: String, private val contents: ByteArray?) : AbstractResource() {
    override fun exists(): Boolean = contents != null
    override fun getInputStream(): InputStream {
        val bytes = contents ?: throw FileNotFoundException(path)
        if (path.endsWith(".yaml") || path.endsWith(".yml")) {
            // Boot's YAML loader expands aliases before flattening the resulting properties.
            UnicodeReader(bytes.inputStream()).use { reader ->
                if (Yaml().parse(reader).any { it is AliasEvent }) {
                    throw ConfigUnavailable("MI_SPRING_CONFIG_YAML_ALIAS", "YAML aliases are outside the local analysis scope.")
                }
            }
        }
        return bytes.inputStream()
    }
    override fun getFilename(): String = path.substringAfterLast('/')
    override fun getDescription(): String = path
}

class LocalConfigImportGuard : ConfigDataLocationResolver<ConfigDataResource> {
    override fun isResolvable(context: ConfigDataLocationResolverContext, location: ConfigDataLocation): Boolean {
        for (entry in location.split()) {
            if (context.parent == null && entry.value.startsWith(CONFIG_PREFIX)) continue
            val parts = entry.value.removePrefix("./").split('/')
            val relativeDocument = parts.dropLast(1).all { it !in setOf(".", "..") && localSegment.matches(it) } &&
                configDocument.matches(parts.last())
            if (!relativeDocument) {
                throw ConfigUnavailable("MI_SPRING_CONFIG_IMPORT_UNKNOWN",
                    "Use a relative properties or YAML import within the configuration root. External or dynamic imports were not evaluated.",
                    entry.origin)
            }
        }
        return false
    }

    override fun resolve(context: ConfigDataLocationResolverContext, location: ConfigDataLocation): List<ConfigDataResource> =
        error("The guard only validates locations for Spring's standard resolver.")

    private companion object {
        val localSegment = Regex("[A-Za-z0-9_.-]+")
    }
}
