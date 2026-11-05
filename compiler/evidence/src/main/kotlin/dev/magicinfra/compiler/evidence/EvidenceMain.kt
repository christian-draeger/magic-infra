package dev.magicinfra.compiler.evidence

import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if (args.size != 1) {
        System.err.println("MI_EVIDENCE_USAGE: Use evidence-check <file or ->.")
        exitProcess(2)
    }
    try {
        if (args[0] == "-") {
            EvidenceReader.read(System.`in`)
        } else {
            Files.newInputStream(Path.of(args[0])).use(EvidenceReader::read)
        }
    } catch (failure: EvidenceRejected) {
        System.err.println("${failure.code}: ${failure.message}")
        exitProcess(failure.status)
    } catch (_: IOException) {
        unreadableInput()
    } catch (_: InvalidPathException) {
        unreadableInput()
    }
}

private fun unreadableInput(): Nothing {
    System.err.println("MI_EVIDENCE_IO: Provide a readable Evidence file or pipe it through stdin.")
    exitProcess(1)
}
