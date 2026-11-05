package dev.magicinfra.compiler.evidence

import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    try {
        when {
            args.size == 1 -> readInput(args[0], read = EvidenceReader::read)
            args.size == 2 && args[0] == "validate-infra" ->
                readInput(args[1], infra = true, read = InfraDeclarationReader::read)
            args.size >= 2 && args[0] == "explain" -> explain(args)
            args.size == 3 && args[0] == "resolve" -> {
                if (args[1] == "-" && args[2] == "-") usage()
                val evidence = readInput(args[1], read = EvidenceReader::read)
                val declaration = readInput(args[2], infra = true, read = InfraDeclarationReader::read)
                print(ApplicationModelResolver.resolve(evidence, declaration))
            }
            else -> usage()
        }
    } catch (failure: EvidenceRejected) {
        System.err.println("${failure.code}: ${failure.message}")
        exitProcess(failure.status)
    } catch (failure: InfraRejected) {
        System.err.println("${failure.code}: ${failure.message}")
        exitProcess(failure.status)
    }
}

private fun explain(args: Array<String>) {
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
    if (suggestions.any { it.resolution == Resolution.CONFLICTED }) exitProcess(4)
}

private fun <T> readInput(path: String, infra: Boolean = false, read: (InputStream) -> T): T = try {
    if (path == "-") read(System.`in`) else Files.newInputStream(Path.of(path)).use(read)
} catch (_: IOException) {
    unreadableInput(infra)
} catch (_: InvalidPathException) {
    unreadableInput(infra)
}

private fun usage(): Nothing {
    System.err.println("MI_EVIDENCE_USAGE: Use evidence-check <file or ->, evidence-check validate-infra <file or ->, " +
        "evidence-check explain <file or -> [--external <service> <postgresql|mysql|h2>], " +
        "or evidence-check resolve <evidence> <infra.yaml>.")
    exitProcess(2)
}

private fun unreadableInput(infra: Boolean): Nothing {
    if (infra) System.err.println("MI_INFRA_IO: Provide a readable declaration file or pipe it through stdin.")
    else System.err.println("MI_EVIDENCE_IO: Provide a readable Evidence file or pipe it through stdin.")
    exitProcess(1)
}
