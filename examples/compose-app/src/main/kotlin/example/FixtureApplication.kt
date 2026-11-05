package example

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication(proxyBeanMethods = false)
class FixtureApplication

fun main(args: Array<String>) {
    runApplication<FixtureApplication>(*args)
}
