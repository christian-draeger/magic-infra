package dev.magicinfra.spring

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.system.exitProcess

private const val MAX_REQUEST_BYTES = 1_048_576
private val requestFields = setOf(
    "schemaVersion", "applicationId", "module", "language", "profiles", "sourceRoots", "dependencies",
)

fun main(args: Array<String>) {
    try {
        if (args.isNotEmpty()) {
            throw ProtocolFailure("MI_SPRING_USAGE", "Pipe one Spring adapter request JSON document to stdin.")
        }
        print(evidence(readRequest(), Path.of("").toAbsolutePath().normalize()))
    } catch (failure: ProtocolFailure) {
        System.err.println(Gson().toJson(mapOf(
            "code" to failure.code, "severity" to "error", "message" to failure.message,
        )))
        exitProcess(failure.status)
    } catch (_: IOException) {
        System.err.println("""{"code":"MI_SPRING_IO","severity":"error","message":"Could not read the adapter request."}""")
        exitProcess(1)
    }
}

private fun readRequest(): AnalysisRequest {
    val bytes = System.`in`.readNBytes(MAX_REQUEST_BYTES + 1)
    if (bytes.size > MAX_REQUEST_BYTES) {
        throw ProtocolFailure("MI_SPRING_INVALID_REQUEST", "Keep the adapter request at or below 1 MiB.")
    }
    val request = try {
        val source = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
        JsonReader(source.reader()).use { reader ->
            reader.strictness = Strictness.STRICT
            reader.beginObject()
            val document = JsonObject()
            while (reader.hasNext()) {
                val key = reader.nextName()
                require(!document.has(key))
                document.add(key, JsonParser.parseReader(reader))
            }
            reader.endObject()
            require(reader.peek() == JsonToken.END_DOCUMENT)
            document
        }
    } catch (_: Exception) {
        throw ProtocolFailure("MI_SPRING_INVALID_REQUEST", "Send exactly one UTF-8 JSON object.")
    }

    val version = request.requiredString("schemaVersion")
    if (version != REQUEST_VERSION) {
        throw ProtocolFailure("MI_SPRING_REQUEST_VERSION", "Use request schemaVersion $REQUEST_VERSION.", 3)
    }
    if (request.keySet() != requestFields) {
        throw ProtocolFailure("MI_SPRING_INVALID_REQUEST",
            "Supply exactly schemaVersion, applicationId, module, language, profiles, sourceRoots and dependencies.")
    }
    return AnalysisRequest(
        applicationId = request.requiredString("applicationId"),
        module = request.requiredString("module"),
        language = request.requiredString("language"),
        profiles = request.stringList("profiles"),
        sourceRoots = request.stringList("sourceRoots"),
        dependencies = request.stringList("dependencies"),
    )
}

private fun JsonObject.requiredString(field: String): String =
    get(field)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        ?: throw ProtocolFailure("MI_SPRING_INVALID_REQUEST", "Set $field to a string.")

private fun JsonObject.stringList(field: String): List<String> {
    val array = get(field)?.takeIf { it.isJsonArray }?.asJsonArray
        ?: throw ProtocolFailure("MI_SPRING_INVALID_REQUEST", "Set $field to a list of strings.")
    return array.map {
        if (!it.isJsonPrimitive || !it.asJsonPrimitive.isString) {
            throw ProtocolFailure("MI_SPRING_INVALID_REQUEST", "Set $field to a list of strings.")
        }
        it.asString
    }
}
