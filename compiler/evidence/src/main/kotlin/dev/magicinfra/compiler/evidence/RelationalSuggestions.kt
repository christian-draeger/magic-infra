package dev.magicinfra.compiler.evidence

import com.fasterxml.jackson.databind.JsonNode

private const val CAPABILITY = "database.relational"
private const val RULE_VERSION = "relational/v1"
private const val JPA_STARTER = "org.springframework.boot:spring-boot-starter-data-jpa"
private val plainIdentifier = Regex("[A-Za-z0-9_-]+")
private val unsafeDisplayCharacter = Regex("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]")
private val jpaAnnotations = setOf(
    "jakarta.persistence.Entity",
    "javax.persistence.Entity",
    "org.springframework.data.jpa.repository.config.EnableJpaRepositories",
)

internal enum class DatabaseVendor(val id: String) {
    POSTGRESQL("postgresql"),
    MYSQL("mysql"),
    H2("h2");

    companion object {
        fun named(id: String): DatabaseVendor? = entries.firstOrNull { it.id == id }
    }
}

internal enum class DatabaseManagement { MANAGED, EXTERNAL }

internal data class DeclaredDatabase(
    val serviceId: String,
    val vendor: DatabaseVendor,
    val management: DatabaseManagement,
)

internal enum class Resolution {
    SUGGESTED, DECLARED, CONFLICTED,
}

internal data class RelationalSuggestion(
    val serviceId: String,
    val resolution: Resolution,
    val vendor: DatabaseVendor?,
    val code: String,
    val explanation: String,
    val findingIds: List<String>,
    val ruleVersion: String = RULE_VERSION,
) {
    fun explain(): String = buildString {
        append(displayIdentifier(serviceId)).append(' ').append(CAPABILITY)
        append(' ').append(resolution.name.lowercase())
        vendor?.let { append(" implementation=").append(it.id) }
        append(" rule=").append(ruleVersion).append(' ').append(code).appendLine()
        append("  ").append(explanation).appendLine()
        if (findingIds.isNotEmpty()) {
            append("  Evidence ").append(findingIds.joinToString(", ", transform = ::displayIdentifier)).appendLine()
        }
    }
}

private fun displayIdentifier(id: String): String {
    if (plainIdentifier.matches(id)) return id
    val escaped = id.replace("\\", "\\\\").replace("\"", "\\\"")
    return "\"" + unsafeDisplayCharacter.replace(escaped) { match ->
        match.value.map { "\\u%04x".format(it.code) }.joinToString("")
    } + "\""
}

internal fun suggestRelational(evidence: JsonNode, declarations: List<DeclaredDatabase> = emptyList()): List<RelationalSuggestion> {
    val services = evidence.path("services").map { it.path("id").asText() }.sorted()
    val declaredByService = declarations.associateBy { it.serviceId }
    if (declaredByService.keys.any { it !in services } || declaredByService.size != declarations.size) {
        throw EvidenceRejected("MI_EVIDENCE_EXPLAIN_SERVICE", "Declare at most one database for each existing service.")
    }
    val byService = evidence.path("findings").filter {
        it.path("capability").asText() == CAPABILITY && it.path("kind").asText() != "declaration"
    }.groupBy { it.path("serviceId").asText() }
    return services.mapNotNull { serviceId ->
        val findings = byService[serviceId].orEmpty()
        val declared = declaredByService[serviceId]
        if (findings.isEmpty() && declared == null) return@mapNotNull null
        assess(serviceId, findings, declared)
    }
}

private fun assess(
    serviceId: String,
    findings: List<JsonNode>,
    declared: DeclaredDatabase?,
): RelationalSuggestion {
    val observed = findings.filter { it.path("certainty").asText() == "observed" }
    val drivers = observed.vendors("dependency")
    val selected = observed.vendors("configuration") + listOfNotNull(declared?.vendor?.id)
    val selection = selected.singleOrNull()
    val conflicted = selected.size > 1 ||
        (selection != null && drivers.isNotEmpty() && selection !in drivers) ||
        (selected.isEmpty() && drivers.size > 1)
    val vendorId = selection ?: drivers.singleOrNull()
    val vendor = vendorId?.let(DatabaseVendor::named)
    val findingIds = findings.map { it.path("id").asText() }.sorted()
    val jpa = observed.any(::isJpaFinding)
    return when {
        conflicted -> RelationalSuggestion(serviceId, Resolution.CONFLICTED, null,
            "MI_RELATIONAL_VENDOR_CONFLICT",
            "Conflicting database vendors: ${displayVendors(selected + drivers)}. Resolve the binding before generating infrastructure.",
            findingIds)
        declared?.management == DatabaseManagement.EXTERNAL -> RelationalSuggestion(serviceId, Resolution.DECLARED, declared.vendor,
            "MI_RELATIONAL_EXTERNAL_DECLARED",
            "The user declared an external database. Use an existing binding.", findingIds)
        declared != null -> RelationalSuggestion(serviceId, Resolution.DECLARED, declared.vendor,
            "MI_RELATIONAL_MANAGED_DECLARED",
            "The user declared a managed database with explicit retention.", findingIds)
        vendor == DatabaseVendor.POSTGRESQL && jpa -> RelationalSuggestion(serviceId, Resolution.SUGGESTED, vendor,
            "MI_RELATIONAL_POSTGRESQL_JPA",
            "JPA and PostgreSQL observations suggest a PostgreSQL binding. Ownership requires an explicit declaration.",
            findingIds)
        vendor != null -> RelationalSuggestion(serviceId, Resolution.SUGGESTED, vendor,
            "MI_RELATIONAL_VENDOR_OBSERVED",
            "A database vendor was observed. Confirm the binding and declare ownership explicitly.", findingIds)
        vendorId != null -> RelationalSuggestion(serviceId, Resolution.SUGGESTED, null,
            "MI_RELATIONAL_VENDOR_UNSUPPORTED",
            "The observed vendor is outside the supported relational families.", findingIds)
        else -> RelationalSuggestion(serviceId, Resolution.SUGGESTED, null,
            "MI_RELATIONAL_VENDOR_UNKNOWN",
            "Relational usage is possible. Confirm the database implementation and ownership.", findingIds)
    }
}

private fun List<JsonNode>.vendors(kind: String): Set<String> =
    filter { it.path("kind").asText() == kind }.mapNotNull {
        it.path("implementation").asText().takeIf(String::isNotBlank)
    }.toSet()

private fun displayVendors(vendors: Set<String>): String =
    vendors.map { DatabaseVendor.named(it)?.id ?: "unsupported vendor" }.distinct().sorted().joinToString()

private fun isJpaFinding(finding: JsonNode): Boolean {
    val kind = finding.path("kind").asText()
    val source = finding.path("source")
    val reference = source.path("reference").asText()
    return (kind == "dependency" && source.path("kind").asText() == "gradle" &&
        (reference == JPA_STARTER || reference.startsWith("$JPA_STARTER:"))) ||
        (kind == "annotation" && source.path("kind").asText() == "bytecode" &&
            finding.path("details").path("annotation").asText() in jpaAnnotations)
}
