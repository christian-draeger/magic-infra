import java.nio.file.Files;
import java.nio.file.Path;

public class FixtureAdapter {
    public static void main(String[] args) throws Exception {
        Files.copy(Path.of(args[0]), System.out);
    }
}
