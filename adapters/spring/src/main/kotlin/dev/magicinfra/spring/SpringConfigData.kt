package dev.magicinfra.spring

import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor
import org.springframework.boot.context.properties.source.ConfigurationPropertyName
import org.springframework.boot.context.properties.source.ConfigurationPropertySources
import org.springframework.boot.origin.Origin
import org.springframework.boot.origin.OriginTrackedResource
import org.springframework.boot.origin.PropertySourceOrigin
import org.springframework.boot.origin.TextResourceOrigin
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Path

private const val DATASOURCE_URL = "spring.datasource.url"
private const val DATASOURCE_DRIVER = "spring.datasource.driver-class-name"
private const val DATASOURCE_JNDI = "spring.datasource.jndi-name"

internal data class ConfigObservations(
    val profiles: List<String>,
    val evaluated: Boolean = false,
    val findings: List<Map<String, Any>> = emptyList(),
    val diagnostics: List<Map<String, Any>> = emptyList(),
)

internal fun inspectConfig(request: AnalysisRequest, project: Path): ConfigObservations {
    val profiles = request.profiles.distinct()
    try {
        val snapshot = LocalConfigSnapshot.read(project, request.sourceRoots)
        if (snapshot.isEmpty) return ConfigObservations(profiles)
        val environment = StandardEnvironment()
        environment.propertySources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)
        environment.propertySources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME)
        val inputs = mutableMapOf<String, Any>(
            "spring.config.location" to request.sourceRoots.distinct().joinToString(",") { "optional:$CONFIG_PREFIX$it/" },
        )
        if (profiles.isNotEmpty()) inputs["spring.profiles.active"] = profiles.joinToString(",")
        environment.propertySources.addFirst(MapPropertySource("magic-infra analysis inputs", inputs))
        ConfigDataEnvironmentPostProcessor.applyTo(environment, snapshot, null)
        val activeProfiles = environment.activeProfiles.toList()
        if (!validProfiles(activeProfiles)) {
            throw ConfigUnavailable("MI_SPRING_CONFIG_PROFILES_UNKNOWN", "Use up to 32 supported local profile names.")
        }

        val findings = mutableListOf<Map<String, Any>>()
        val diagnostics = mutableListOf<Map<String, Any>>()
        diagnostics += diagnostic("MI_SPRING_CONFIG_CUSTOM_SOURCES_UNKNOWN", "info",
            "Application-defined property sources and runtime environment overrides were not evaluated.")
        for (key in listOf(DATASOURCE_URL, DATASOURCE_DRIVER, DATASOURCE_JNDI)) {
            val name = ConfigurationPropertyName.of(key)
            val property = ConfigurationPropertySources.get(environment).firstNotNullOfOrNull { it.getConfigurationProperty(name) }
                ?: continue
            val source = configSource(key, property.origin)
            val value = try {
                environment.resolveRequiredPlaceholders(property.value.toString()).takeUnless { it.contains("\${") }
            } catch (_: IllegalArgumentException) {
                null
            }
            val implementation = when (key) {
                DATASOURCE_URL -> when {
                    value?.startsWith("jdbc:postgresql:") == true -> "postgresql"
                    value?.startsWith("jdbc:mysql:") == true -> "mysql"
                    value?.startsWith("jdbc:h2:") == true -> "h2"
                    else -> null
                }
                DATASOURCE_DRIVER -> when (value) {
                    "org.postgresql.Driver" -> "postgresql"
                    "com.mysql.cj.jdbc.Driver" -> "mysql"
                    "org.h2.Driver" -> "h2"
                    else -> null
                }
                else -> null
            }
            when {
                value == null -> diagnostics += diagnostic("MI_SPRING_CONFIG_PLACEHOLDER_UNKNOWN", "warning",
                    "A datasource property has an unresolved placeholder in the local snapshot.", source)
                key == DATASOURCE_JNDI -> diagnostics += diagnostic("MI_SPRING_CONFIG_CUSTOM_DATASOURCE_UNKNOWN", "warning",
                    "A JNDI datasource requires runtime configuration.", source)
                implementation == null -> diagnostics += diagnostic("MI_SPRING_CONFIG_DATASOURCE_UNKNOWN", "warning",
                    "The datasource configuration uses an unrecognized JDBC implementation.", source)
                key == DATASOURCE_URL -> diagnostics += diagnostic("MI_SPRING_CONFIG_DATASOURCE_URL", "info",
                    "A local datasource URL was observed. It does not authorize provisioning.", source)
            }
            findings += linkedMapOf<String, Any>(
                "id" to "f-config-${key.substringAfterLast('.')}",
                "serviceId" to request.applicationId,
                "kind" to "configuration",
                "capability" to "database.relational",
                "source" to source,
                "certainty" to if (implementation == null) "uncertain" else "observed",
                "details" to mapOf("key" to key),
            ).apply { implementation?.let { put("implementation", it) } }
        }
        return ConfigObservations(activeProfiles, true, findings, diagnostics)
    } catch (failure: ConfigUnavailable) {
        return unavailable(profiles, failure)
    } catch (_: IOException) {
        return unreadableConfig(profiles)
    } catch (_: UncheckedIOException) {
        return unreadableConfig(profiles)
    } catch (failure: RuntimeException) {
        // Spring errors can contain credentials or import URLs. Do not pass them through.
        val known = generateSequence<Throwable>(failure) { it.cause }.take(16).filterIsInstance<ConfigUnavailable>().firstOrNull()
        return unavailable(profiles, known ?: ConfigUnavailable("MI_SPRING_CONFIG_INVALID",
            "Local configuration could not be evaluated. Check document syntax, profiles and required imports."))
    }
}

private fun unreadableConfig(profiles: List<String>) = unavailable(profiles,
    ConfigUnavailable("MI_SPRING_CONFIG_READ", "Local configuration files could not be read."))

private fun unavailable(profiles: List<String>, failure: ConfigUnavailable): ConfigObservations {
    val source = failure.origin?.let { configSource("spring.config.import", it) }
    return ConfigObservations(profiles, diagnostics = listOf(diagnostic(failure.code, "warning", failure.message.orEmpty(), source)))
}

private fun configSource(key: String, origin: Origin?): Map<String, Any> {
    val source = linkedMapOf<String, Any>("kind" to "spring-property", "reference" to key)
    val text = (if (origin is PropertySourceOrigin) origin.origin else origin) as? TextResourceOrigin ?: return source
    val resource = text.resource.let { if (it is OriginTrackedResource) it.resource else it }
    if (resource is LocalConfigResource) source["path"] = resource.path
    text.location?.let { source["line"] = it.line + 1 }
    return source
}

private fun diagnostic(code: String, severity: String, message: String, source: Map<String, Any>? = null): Map<String, Any> =
    linkedMapOf<String, Any>("code" to code, "severity" to severity, "message" to message).apply {
        source?.let { put("source", it) }
    }
