package example

import jakarta.persistence.Entity
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

@Entity
class KotlinRecord

@Configuration(proxyBeanMethods = false)
@Profile("sample-secret-value")
class KotlinDatabase {
    @Bean("dataSource")
    fun customPool(): Any = error("The bytecode scanner must not instantiate a bean.")
}
