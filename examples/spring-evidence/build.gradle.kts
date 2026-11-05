plugins {
    id("org.springframework.boot") version "3.5.16" apply false
    kotlin("jvm") version "2.2.21" apply false
}

subprojects {
    repositories {
        mavenCentral()
    }
}
