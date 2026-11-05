package dev.magicinfra.spring

import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.HexFormat

private const val BEAN = "Lorg/springframework/context/annotation/Bean;"
private const val MAX_CLASSES = 4096
private const val MAX_CLASS_BYTES = 1024 * 1024
private const val MAX_SCAN_BYTES = 32 * 1024 * 1024
private const val MAX_RECORDS = 512
private const val MAX_SOURCE_CHARACTERS = 768

private val relationalAnnotations = setOf(
    "Ljakarta/persistence/Entity;",
    "Ljavax/persistence/Entity;",
    "Lorg/springframework/data/jpa/repository/config/EnableJpaRepositories;",
)
private val propertySourceAnnotations = setOf(
    "Lorg/springframework/context/annotation/PropertySource;",
    "Lorg/springframework/context/annotation/PropertySources;",
)
private val datasourceTypes = setOf(
    "Ljavax/sql/DataSource;",
    "Lcom/zaxxer/hikari/HikariDataSource;",
    "Lorg/apache/commons/dbcp2/BasicDataSource;",
    "Lorg/springframework/jdbc/datasource/DriverManagerDataSource;",
)

internal data class BytecodeObservations(
    val findings: List<Map<String, Any>>,
    val diagnostics: List<Map<String, Any>>,
)

internal fun inspectBytecode(applicationId: String, project: Path, files: Set<File>): BytecodeObservations {
    val findings = mutableListOf<Map<String, Any>>()
    val diagnostics = mutableListOf<Map<String, Any>>()
    if (files.size > MAX_CLASSES) {
        diagnostics += diagnostic("MI_SPRING_BYTECODE_LIMIT", "warning", "The selected module has more than 4096 class files.")
        return BytecodeObservations(findings, diagnostics)
    }
    var remainingBytes = MAX_SCAN_BYTES
    fun hasRoom(records: Int): Boolean {
        if (findings.size + diagnostics.size + records <= MAX_RECORDS) return true
        diagnostics += diagnostic("MI_SPRING_BYTECODE_LIMIT", "warning", "Bytecode analysis reached its Evidence record limit.")
        return false
    }
    scan@ for (file in files.sortedBy { it.path }) {
        if (!hasRoom(1)) break
        val path = file.toPath().toAbsolutePath().normalize()
        if (!path.startsWith(project) || generateSequence(path) { it.parent }.takeWhile { it != project }.any(Files::isSymbolicLink)) {
            diagnostics += diagnostic("MI_SPRING_BYTECODE_PATH_UNKNOWN", "warning",
                "A class file is outside the local module snapshot or uses a symbolic link.")
            continue
        }
        val relative = project.relativize(path).joinToString("/")
        if (relative.length > MAX_SOURCE_CHARACTERS / 2) {
            diagnostics += sourceTooLong()
            continue
        }
        val location = bytecodeSource(relative, relative)
        try {
            val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            if (!attributes.isRegularFile) throw IOException()
            val size = attributes.size()
            if (size > MAX_CLASS_BYTES) {
                diagnostics += diagnostic("MI_SPRING_BYTECODE_LIMIT", "warning",
                    "A class file exceeded the per-file size limit.", location)
                continue
            }
            if (size > remainingBytes) {
                diagnostics += diagnostic("MI_SPRING_BYTECODE_LIMIT", "warning", "Class files exceeded the total read limit.")
                break
            }
            val bytes = Files.newInputStream(path, NOFOLLOW_LINKS).use { it.readNBytes(size.toInt()) }
            remainingBytes -= bytes.size
            require(bytes.size >= 4 && ByteBuffer.wrap(bytes).int == 0xCAFEBABE.toInt())
            val reader = ClassReader(bytes)
            val metadata = ClassMetadata()
            try {
                reader.accept(metadata, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
            } catch (_: StackOverflowError) {
                diagnostics += unreadableClass(location)
                continue
            }
            val className = reader.className.replace('/', '.')
            if (className.length + relative.length > MAX_SOURCE_CHARACTERS) {
                diagnostics += sourceTooLong()
                continue
            }
            val source = bytecodeSource(className, relative)
            for (annotation in metadata.annotations.sorted()) {
                if (annotation in relationalAnnotations) {
                    if (!hasRoom(1)) break@scan
                    findings += bytecodeFinding(applicationId, "annotation", annotation, source)
                }
                if (annotation in propertySourceAnnotations) {
                    if (!hasRoom(1)) break@scan
                    diagnostics += diagnostic("MI_SPRING_BYTECODE_PROPERTY_SOURCE_UNKNOWN", "warning",
                        "Application code declares an additional property source outside local ConfigData.", source)
                }
            }
            for (bean in metadata.beans.sortedBy { it.signature }) {
                val reference = "$className#${bean.signature}"
                if (reference.length + relative.length > MAX_SOURCE_CHARACTERS) {
                    if (!hasRoom(1)) break@scan
                    diagnostics += sourceTooLong()
                    continue
                }
                val conditional = bean.conditional || metadata.annotations.any(::isConditional)
                if (!hasRoom(if (conditional) 3 else 2)) break@scan
                val beanSource = bytecodeSource(reference, relative)
                findings += bytecodeFinding(applicationId, "bean", BEAN, beanSource)
                diagnostics += diagnostic("MI_SPRING_BYTECODE_DATASOURCE_UNKNOWN", "warning",
                    "Application code declares a datasource bean. Its runtime implementation and binding are unknown.", beanSource)
                if (conditional) {
                    diagnostics += diagnostic("MI_SPRING_BYTECODE_CONDITIONAL_BEAN_UNKNOWN", "warning",
                        "A datasource bean depends on a condition or profile that static analysis cannot evaluate.", beanSource)
                }
            }
        } catch (_: IOException) {
            diagnostics += unreadableClass(location)
        } catch (_: RuntimeException) {
            diagnostics += unreadableClass(location)
        }
    }
    return BytecodeObservations(findings, diagnostics.distinct())
}

private fun unreadableClass(source: Map<String, Any>) = diagnostic("MI_SPRING_BYTECODE_CLASS_UNKNOWN", "warning",
    "A class file could not be read or uses an unsupported format.", source)

private fun sourceTooLong() = diagnostic("MI_SPRING_BYTECODE_LIMIT", "warning",
    "A bytecode source reference exceeded the provenance length limit.")

private fun bytecodeSource(reference: String, path: String): Map<String, Any> =
    mapOf("kind" to "bytecode", "reference" to reference, "path" to path)

private fun bytecodeFinding(
    applicationId: String,
    kind: String,
    descriptor: String,
    source: Map<String, Any>,
): Map<String, Any> {
    val key = "$kind|${source["path"]}|${source["reference"]}|$descriptor"
    val hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.toByteArray()))
    return linkedMapOf(
        "id" to "f-bytecode-$hash",
        "serviceId" to applicationId,
        "kind" to kind,
        "capability" to "database.relational",
        "source" to source,
        "certainty" to if (kind == "bean") "uncertain" else "observed",
        "details" to mapOf("annotation" to Type.getType(descriptor).className),
    )
}

private fun isConditional(descriptor: String): Boolean =
    descriptor == "Lorg/springframework/context/annotation/Conditional;" ||
        descriptor == "Lorg/springframework/context/annotation/Profile;" ||
        descriptor.startsWith("Lorg/springframework/boot/autoconfigure/condition/ConditionalOn")

private data class DatasourceBean(val signature: String, val conditional: Boolean)

private class ClassMetadata : ClassVisitor(Opcodes.ASM9) {
    val annotations = mutableSetOf<String>()
    val beans = mutableListOf<DatasourceBean>()
    private val signatures = mutableSetOf<String>()

    override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? {
        annotations += descriptor
        return null
    }

    override fun visitMethod(
        access: Int,
        name: String,
        descriptor: String,
        signature: String?,
        exceptions: Array<out String>?,
    ): MethodVisitor? {
        require(signatures.add("$name$descriptor"))
        if (access and (Opcodes.ACC_SYNTHETIC or Opcodes.ACC_BRIDGE) != 0) return null
        return object : MethodVisitor(Opcodes.ASM9) {
            private var bean = false
            private var namedDataSource = false
            private var conditional = false

            override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? {
                if (isConditional(descriptor)) conditional = true
                if (descriptor != BEAN) return null
                bean = true
                return object : AnnotationVisitor(Opcodes.ASM9) {
                    override fun visitArray(name: String): AnnotationVisitor? {
                        if (name !in setOf("name", "value")) return null
                        return object : AnnotationVisitor(Opcodes.ASM9) {
                            override fun visit(name: String?, value: Any?) {
                                if (value == "dataSource") namedDataSource = true
                            }
                        }
                    }
                }
            }

            override fun visitEnd() {
                if (bean && (name == "dataSource" || namedDataSource || Type.getReturnType(descriptor).descriptor in datasourceTypes)) {
                    beans += DatasourceBean("$name$descriptor", conditional)
                }
            }
        }
    }
}
