plugins {
    java
    id("org.springframework.boot")
    id("dev.magicinfra.spring-evidence")
}

dependencies {
    implementation(platform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES))
    implementation("org.springframework.boot:spring-boot-starter")
    compileOnly("jakarta.persistence:jakarta.persistence-api")
    runtimeOnly("org.postgresql:postgresql")
}

if (providers.gradleProperty("fixture.unresolved").isPresent) {
    dependencies {
        runtimeOnly("dev.magicinfra.fixture:missing-runtime-component:1.0")
    }
}

if (providers.gradleProperty("fixture.conflict").isPresent) {
    dependencies {
        runtimeOnly("com.mysql:mysql-connector-j")
    }
}
