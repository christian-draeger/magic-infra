package dev.magicinfra.spring

import com.google.gson.GsonBuilder

internal const val REQUEST_VERSION = "spring-adapter-request/v1alpha1"
private val json = GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create()

internal data class AnalysisRequest(
    val applicationId: String,
    val module: String,
    val language: String,
    val profiles: List<String>,
    val sourceRoots: List<String>,
    val dependencies: List<String>,
)

internal class ProtocolFailure(val code: String, message: String, val status: Int = 2) :
    IllegalArgumentException(message)

private val applicationId = Regex("[a-z][a-z0-9-]{0,62}")
private val modulePath = Regex(":(?:[A-Za-z0-9_-][A-Za-z0-9_.-]*(?::[A-Za-z0-9_-][A-Za-z0-9_.-]*)*)?")
private val profile = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")
private val pathSegment = Regex("[A-Za-z0-9_.-]+")
private val coordinate = Regex("[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[A-Za-z0-9_.+~-]+")

internal fun validateContext(request: AnalysisRequest) {
    fun check(condition: Boolean, message: String) {
        if (!condition) throw ProtocolFailure("MI_SPRING_INVALID_REQUEST", message)
    }
    check(applicationId.matches(request.applicationId), "Set applicationId to a lowercase identifier starting with a letter.")
    check(modulePath.matches(request.module), "Set module to a Gradle project path, such as :api.")
    check(request.language in setOf("java", "kotlin"), "Set language to java or kotlin.")
    check(request.profiles.size <= 32 && request.profiles.all(profile::matches),
        "Use at most 32 profile names with letters, digits, dots, underscores or hyphens.")
    check(request.sourceRoots.size <= 32 && request.sourceRoots.all { root ->
        root.length <= 240 && root.split('/').all { it !in setOf(".", "..") && pathSegment.matches(it) }
    }, "Use at most 32 project-relative source roots with forward slashes and no traversal.")
    check(request.dependencies.size <= 8192 && request.dependencies.all { it.length <= 240 && coordinate.matches(it) },
        "Supply resolved Maven group:name:version coordinates.")
}

private data class DependencyRule(val id: String, val implementation: String? = null)

private val rules = mapOf(
    "org.springframework.boot:spring-boot-starter-data-jpa" to DependencyRule("jpa-starter"),
    "org.springframework.boot:spring-boot-starter-jdbc" to DependencyRule("jdbc-starter"),
    "org.postgresql:postgresql" to DependencyRule("postgresql-driver", "postgresql"),
    "com.mysql:mysql-connector-j" to DependencyRule("mysql-driver", "mysql"),
    "com.h2database:h2" to DependencyRule("h2-driver", "h2"),
)

internal fun dependencyOfInterest(group: String, name: String): Boolean =
    "$group:$name" in rules || "$group:$name" == "org.springframework.boot:spring-boot"

internal fun evidence(request: AnalysisRequest): String {
    validateContext(request)
    val coordinates = request.dependencies.distinct().sorted()
    val bootVersion = coordinates.singleOrNull { it.substringBeforeLast(':') == "org.springframework.boot:spring-boot" }
        ?.substringAfterLast(':')
    val findings = coordinates.mapNotNull { gav ->
        val rule = rules[gav.substringBeforeLast(':')] ?: return@mapNotNull null
        linkedMapOf(
            "id" to "f-${rule.id}",
            "serviceId" to request.applicationId,
            "kind" to "dependency",
            "capability" to "database.relational",
            "source" to mapOf("kind" to "gradle", "reference" to gav),
            "certainty" to "observed",
        ).apply {
            rule.implementation?.let { put("implementation", it) }
        }
    }
    if (findings.map { it["id"] }.toSet().size != findings.size) {
        throw ProtocolFailure("MI_SPRING_AMBIGUOUS_COMPONENT", "Supply one resolved version per recognized component.")
    }

    val diagnostics = if (bootVersion == null) listOf(mapOf(
        "code" to "MI_SPRING_BOOT_RUNTIME_UNKNOWN",
        "severity" to "warning",
        "message" to "Provide a runtime snapshot with one resolved Spring Boot component to identify its version.",
    )) else emptyList()

    val application = linkedMapOf(
        "id" to request.applicationId,
        "framework" to "spring-boot",
        "language" to request.language,
    ).apply {
        bootVersion?.let { put("frameworkVersion", it) }
    }
    return json.toJson(linkedMapOf(
        "schemaVersion" to "evidence/v1alpha1",
        "adapter" to mapOf("id" to "spring-boot", "version" to "0.1.0-alpha.1", "rulesVersion" to "1"),
        "application" to application,
        "analysis" to mapOf(
            "profiles" to request.profiles.distinct().sorted(),
            "environmentScope" to "explicit-snapshot",
            "limitations" to listOf(
                "Only selected components on the resolved runtime classpath are inspected.",
                "Spring configuration, application classes and resource ownership are outside this snapshot.",
            ),
        ),
        "services" to listOf(mapOf("id" to request.applicationId, "module" to request.module, "buildSystem" to "gradle")),
        "findings" to findings,
        "diagnostics" to diagnostics,
    )) + "\n"
}
