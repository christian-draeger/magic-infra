import dev.magicinfra.spring.AnalysisRequest;
import dev.magicinfra.spring.SpringEvidenceKt;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.stream.Collectors;

public class SpringBytecodeProbe {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toAbsolutePath();
        if (args.length == 2) {
            writeClasses(root);
        }
        var request = new AnalysisRequest("probe", ":probe", "java", List.of(), List.of(), List.of());
        try (var files = Files.list(root)) {
            var classes = files.filter(path -> path.toString().endsWith(".class"))
                .map(Path::toFile).collect(Collectors.toSet());
            System.out.print(SpringEvidenceKt.evidence(request, root, classes));
        }
    }

    private static void writeClasses(Path root) throws Exception {
        var invalidHeader = type("InvalidHeader");
        invalidHeader.visitAnnotation("Ljakarta/persistence/Entity;", true).visitEnd();
        byte[] header = invalidHeader.toByteArray();
        header[0] = 0;
        Files.write(root.resolve("a-invalid-header.class"), header);

        var duplicate = type("Duplicate");
        bean(duplicate, "same");
        bean(duplicate, "same");
        Files.write(root.resolve("b-duplicate.class"), duplicate.toByteArray());

        var deep = type("Deep");
        var values = new ArrayDeque<AnnotationVisitor>();
        values.push(deep.visitAnnotation("Ljakarta/persistence/Entity;", true));
        for (int i = 0; i < 10000; i++) {
            values.push(values.peek().visitArray("nested"));
        }
        while (!values.isEmpty()) {
            values.pop().visitEnd();
        }
        Files.write(root.resolve("c-deep.class"), deep.toByteArray());

        var burst = type("Burst");
        burst.visitAnnotation("Lorg/springframework/context/annotation/Profile;", true).visitEnd();
        for (int i = 0; i < 6500; i++) {
            bean(burst, "source" + i);
        }
        Files.write(root.resolve("e-burst.class"), burst.toByteArray());
    }

    private static ClassWriter type(String name) {
        var writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        return writer;
    }

    private static void bean(ClassWriter writer, String name) {
        var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_NATIVE,
            name, "()Ljavax/sql/DataSource;", null, null);
        method.visitAnnotation("Lorg/springframework/context/annotation/Bean;", true).visitEnd();
        method.visitEnd();
    }
}
