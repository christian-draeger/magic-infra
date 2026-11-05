package example;

import jakarta.persistence.Entity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;

import javax.sql.DataSource;

@Configuration
@PropertySource("file:/sample-secret-value.properties")
public class JavaDatabase {
    @Entity
    public static class JavaRecord {
    }

    @Bean
    @ConditionalOnProperty("sample-secret-value")
    public DataSource customPool() {
        throw new AssertionError("The bytecode scanner must not instantiate a bean.");
    }
}
