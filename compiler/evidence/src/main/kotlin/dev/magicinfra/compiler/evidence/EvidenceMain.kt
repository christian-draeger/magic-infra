package dev.magicinfra.compiler.evidence

import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val explain = args.size >= 2 && args[0] == "explain"
    val infra = args.size == 2 && args[0] == "validate-infra"
    val external = when {
        args.size == 1 || infra || (explain && args.size == 2) -> null
        explain && args.size == 5 && args[2] == "--external" -> {
            val vendor = DatabaseVendor.named(args[4]) ?: usage()
            ExternalDatabase(args[3], vendor)
        }
        else -> usage()
    }
    val input = args[if (explain || infra) 1 else 0]
    try {
        if (infra) {
            readInput(input, InfraDeclarationReader::read)
            return
        }
        val evidence = readInput(input, EvidenceReader::read)
        if (explain) {
            val suggestions = suggestRelational(evidence, external)
            if (suggestions.isEmpty()) println("No relational findings.")
            suggestions.forEach { print(it.explain()) }
            if (suggestions.any { it.resolution == Resolution.CONFLICTED }) exitProcess(4)
        }
    } catch (failure: EvidenceRejected) {
        System.err.println("${failure.code}: ${failure.message}")
        exitProcess(failure.status)
    } catch (failure: InfraRejected) {
        System.err.println("${failure.code}: ${failure.message}")
        exitProcess(failure.status)
    } catch (_: IOException) {
        unreadableInput(infra)
    } catch (_: InvalidPathException) {
        unreadableInput(infra)
    }
}

private fun <T> readInput(path: String, read: (InputStream) -> T): T =
    if (path == "-") read(System.`in`) else Files.newInputStream(Path.of(path)).use(read)

private fun usage(): Nothing {
    System.err.println("MI_EVIDENCE_USAGE: Use evidence-check <file or ->, evidence-check validate-infra <file or ->, " +
        "or evidence-check explain <file or -> [--external <service> <postgresql|mysql|h2>].")
    exitProcess(2)
}

private fun unreadableInput(infra: Boolean): Nothing {
    if (infra) System.err.println("MI_INFRA_IO: Provide a readable declaration file or pipe it through stdin.")
    else System.err.println("MI_EVIDENCE_IO: Provide a readable Evidence file or pipe it through stdin.")
    exitProcess(1)
}
