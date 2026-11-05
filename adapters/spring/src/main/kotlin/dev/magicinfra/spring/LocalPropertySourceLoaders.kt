package dev.magicinfra.spring

import org.springframework.boot.env.PropertiesPropertySourceLoader
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.boot.origin.OriginLookup
import org.springframework.core.env.EnumerablePropertySource
import org.springframework.core.env.PropertySource
import org.springframework.core.io.Resource

class LocalPropertiesLoader : PropertiesPropertySourceLoader() {
    override fun load(name: String, resource: Resource): List<PropertySource<*>> =
        super.load(name, resource).checkedControls()
}

class LocalYamlLoader : YamlPropertySourceLoader() {
    override fun load(name: String, resource: Resource): List<PropertySource<*>> =
        super.load(name, resource).checkedControls()
}

private fun List<PropertySource<*>>.checkedControls(): List<PropertySource<*>> {
    for (source in filterIsInstance<EnumerablePropertySource<*>>()) {
        for (key in source.propertyNames) {
            val normalized = key.lowercase().filter(Char::isLetterOrDigit)
            val reference = when {
                normalized.startsWith("springconfigimport") -> "spring.config.import"
                normalized.startsWith("springprofiles") -> "spring.profiles"
                normalized.startsWith("springconfig") -> "spring.config"
                normalized == "springmaincloudplatform" -> "spring.main.cloud-platform"
                else -> continue
            }
            val value = source.getProperty(key) as? String ?: continue
            if (!value.contains("\${")) continue
            val code = if (reference == "spring.config.import") "MI_SPRING_CONFIG_IMPORT_UNKNOWN"
                else "MI_SPRING_CONFIG_CONTROL_UNKNOWN"
            throw ConfigUnavailable(code,
                "A ConfigData control uses a placeholder. Supply literal local imports and profile settings.",
                OriginLookup.getOrigin(source, key), reference)
        }
    }
    return this
}
