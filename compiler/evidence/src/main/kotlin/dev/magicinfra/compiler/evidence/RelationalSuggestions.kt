package dev.magicinfra.compiler.evidence

import com.fasterxml.jackson.databind.JsonNode

private const val CAPABILITY = "database.relational"
private const val RULE_VERSION = "relational/v1"
private const val JPA_STARTER = "org.springframework.boot:spring-boot-starter-data-jpa"
private val controlCharacter = Regex("[\\p{Cntrl}]")
private val vendorEvidence = setOf("dependency", "configuration")
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

internal data class ExternalDatabase(val serviceId: String, val vendor: DatabaseVendor)

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
        append(serviceId.replace(controlCharacter, "_")).append(' ').append(CAPABILITY)
        append(' ').append(resolution.name.lowercase())
        vendor?.let { append(" implementation=").append(it.id) }
        append(" rule=").append(ruleVersion).append(' ').append(code).appendLine()
        append("  ").append(explanation).appendLine()
        if (findingIds.isNotEmpty()) {
            append("  Evidence ").append(findingIds.joinToString(", ") { it.trimEnd() }).appendLine()
        }
    }
}

internal fun suggestRelational(evidence: JsonNode, external: ExternalDatabase? = null): List<RelationalSuggestion> {
    val services = evidence.path("services").map { it.path("id").asText() }.sorted()
    if (external != null && external.serviceId !in services) {
        throw EvidenceRejected("MI_EVIDENCE_EXPLAIN_SERVICE", "Name an existing service when declaring an external database.")
    }
    val byService = evidence.path("findings").filter {
        it.path("capability").asText() == CAPABILITY && it.path("kind").asText() != "declaration"
    }.groupBy { it.path("serviceId").asText() }
    return services.mapNotNull { serviceId ->
        val findings = byService[serviceId].orEmpty()
        val declared = external?.takeIf { it.serviceId == serviceId }?.vendor
        if (findings.isEmpty() && declared == null) return@mapNotNull null
        assess(serviceId, findings, declared)
    }
}

private fun assess(
    serviceId: String,
    findings: List<JsonNode>,
    declared: DatabaseVendor?,
): RelationalSuggestion {
    val observed = findings.filter { it.path("certainty").asText() == "observed" }
    val vendors = observed.filter { it.path("kind").asText() in vendorEvidence }
        .mapNotNull { DatabaseVendor.named(it.path("implementation").asText()) }.toMutableSet()
    declared?.let(vendors::add)
    val vendor = vendors.singleOrNull()
    val findingIds = findings.map { it.path("id").asText() }.sorted()
    val jpa = observed.any(::isJpaFinding)
    return when {
        vendors.size > 1 -> RelationalSuggestion(serviceId, Resolution.CONFLICTED, null,
            "MI_RELATIONAL_VENDOR_CONFLICT",
            "Conflicting database vendors: ${vendors.map(DatabaseVendor::id).sorted().joinToString()}. Resolve the binding before generating infrastructure.",
            findingIds)
        declared != null -> RelationalSuggestion(serviceId, Resolution.DECLARED, declared,
            "MI_RELATIONAL_EXTERNAL_DECLARED",
            "The user declared an external database. Use an existing binding.", findingIds)
        vendor == DatabaseVendor.POSTGRESQL && jpa -> RelationalSuggestion(serviceId, Resolution.SUGGESTED, vendor,
            "MI_RELATIONAL_POSTGRESQL_JPA",
            "JPA and PostgreSQL observations suggest a PostgreSQL binding. Ownership requires an explicit declaration.",
            findingIds)
        vendor != null -> RelationalSuggestion(serviceId, Resolution.SUGGESTED, vendor,
            "MI_RELATIONAL_VENDOR_OBSERVED",
            "A database vendor was observed. Confirm the binding and declare ownership explicitly.", findingIds)
        else -> RelationalSuggestion(serviceId, Resolution.SUGGESTED, null,
            "MI_RELATIONAL_VENDOR_UNKNOWN",
            "Relational usage is possible. Confirm the database implementation and ownership.", findingIds)
    }
}

private fun isJpaFinding(finding: JsonNode): Boolean {
    val kind = finding.path("kind").asText()
    val source = finding.path("source")
    val reference = source.path("reference").asText()
    return (kind == "dependency" && source.path("kind").asText() == "gradle" &&
        (reference == JPA_STARTER || reference.startsWith("$JPA_STARTER:"))) ||
        (kind == "annotation" && source.path("kind").asText() == "bytecode" &&
            finding.path("details").path("annotation").asText() in jpaAnnotations)
}
