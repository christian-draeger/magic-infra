package dev.magicinfra.compiler.evidence

import com.fasterxml.jackson.core.util.DefaultIndenter
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.json.JsonMapper
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.system.exitProcess

private val json = JsonMapper.builder().build()
private val writer = json.writer(DefaultPrettyPrinter().withObjectIndenter(DefaultIndenter("  ", "\n")))
private val applicationId = Regex("[a-z][a-z0-9-]{0,62}")
private val commands = setOf("init", "analyze", "validate-infra", "explain", "resolve", "plan", "generate")

fun main(rawArgs: Array<String>) {
    val structured = rawArgs.firstOrNull() == "--diagnostics=json"
    val args = if (structured) rawArgs.drop(1).toTypedArray() else rawArgs
    try {
        when {
            args.size == 1 && args[0] !in commands -> readInput(args[0], read = EvidenceReader::read)
            args.size == 4 && args[0] == "init" -> init(args[1], args[2], args[3])
            args.size == 2 && args[0] == "analyze" -> writeJson(readInput(args[1], read = EvidenceReader::read))
            args.size == 2 && args[0] == "validate-infra" ->
                readInput(args[1], infra = true, read = InfraDeclarationReader::read)
            args.size >= 2 && args[0] == "explain" -> explain(args, structured)
            args.size == 3 && args[0] == "resolve" -> compile(args[1], args[2])
            args.size in 3..5 && args[0] in setOf("plan", "generate") -> target(args)
            else -> usage()
        }
    } catch (failure: EvidenceRejected) {
        fail(structured, failure.code, failure.message.orEmpty(), failure.status)
    } catch (failure: InfraRejected) {
        fail(structured, failure.code, failure.message.orEmpty(), failure.status)
    }
}

private fun init(id: String, image: String, port: String) {
    val number = port.toIntOrNull()
    if (!applicationId.matches(id) || number == null || number !in 1..65535) {
        throw InfraRejected("MI_INFRA_INIT_INPUT", "Use a lowercase application ID and a port between 1 and 65535.")
    }
    if (image.length > 320) {
        throw InfraRejected("MI_INFRA_INIT_INPUT", "Use a tagged or digest-pinned image reference up to 320 characters.")
    }
    val document = """
        schemaVersion: infra/v1alpha2
        applicationId: $id
        services:
          $id:
            imageRef: ${json.writeValueAsString(image)}
            port: $number
        resources: {}
        bindings: []
    """.trimIndent() + "\n"
    document.byteInputStream().use(InfraDeclarationReader::read)
    print(document)
}

private fun target(args: Array<String>) {
    val options = args.drop(3)
    if ("--require-host-failover" in options) {
        throw EvidenceRejected("MI_COMPOSE_HOST_FAILOVER",
            "Single-host Compose cannot provide host failover. Select a target with multiple hosts.", 4)
    }
    if (options.size > 1 || options.any { it.startsWith("--") }) usage()
    compile(args[1], args[2], options.singleOrNull() ?: "dev", args[0] == "generate")
}

private fun compile(evidencePath: String, declarationPath: String, environment: String? = null, render: Boolean = false) {
    if (evidencePath == "-" && declarationPath == "-") usage()
    val evidence = readInput(evidencePath, read = EvidenceReader::read)
    val declaration = readInput(declarationPath, infra = true, read = InfraDeclarationReader::read)
    val model = ApplicationModelResolver.resolve(evidence, declaration)
    if (environment == null) {
        writeJson(model)
        return
    }
    val plan = DeploymentPlanPreview.plan(model, evidence, environment)
    if (render) print(ComposeRenderer.render(model, plan)) else writeJson(plan)
}

private fun explain(args: Array<String>, structured: Boolean) {
    val declarations = when {
        args.size == 2 -> emptyList()
        args.size == 5 && args[2] == "--external" -> {
            val vendor = DatabaseVendor.named(args[4]) ?: usage()
            listOf(DeclaredDatabase(args[3], vendor, DatabaseManagement.EXTERNAL))
        }
        else -> usage()
    }
    val evidence = readInput(args[1], read = EvidenceReader::read)
    val suggestions = suggestRelational(evidence, declarations)
    if (suggestions.isEmpty()) println("No relational findings.")
    suggestions.forEach { print(it.explain()) }
    if (suggestions.any { it.resolution == Resolution.CONFLICTED }) {
        if (structured) fail(true, "MI_RELATIONAL_VENDOR_CONFLICT", "Resolve the reported database vendor conflicts.", 4)
        exitProcess(4)
    }
}

private fun writeJson(document: JsonNode) {
    print(writer.writeValueAsString(document) + "\n")
}

private fun <T> readInput(path: String, infra: Boolean = false, read: (InputStream) -> T): T = try {
    if (path == "-") read(System.`in`) else Files.newInputStream(Path.of(path)).use(read)
} catch (_: IOException) {
    unreadableInput(infra)
} catch (_: InvalidPathException) {
    unreadableInput(infra)
}

private fun usage(): Nothing = throw EvidenceRejected("MI_EVIDENCE_USAGE",
    "Use evidence-check with init <id> <image> <port>, analyze <evidence>, validate-infra <declaration>, " +
        "explain <evidence>, resolve <evidence> <declaration>, or plan/generate <evidence> <declaration> [environment].")

private fun unreadableInput(infra: Boolean): Nothing {
    if (infra) throw InfraRejected("MI_INFRA_IO", "Provide a readable declaration file or pipe it through stdin.", 1)
    throw EvidenceRejected("MI_EVIDENCE_IO", "Provide a readable Evidence file or pipe it through stdin.", 1)
}

private fun fail(structured: Boolean, code: String, message: String, status: Int): Nothing {
    if (structured) {
        System.err.println(json.writeValueAsString(mapOf("code" to code, "severity" to "error", "message" to message)))
    } else {
        System.err.println("$code: $message")
    }
    exitProcess(status)
}
