package example;

import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class JavaApplication {
    public static void main(String[] args) {
        throw new AssertionError("The evidence task must not run the application.");
    }
}
