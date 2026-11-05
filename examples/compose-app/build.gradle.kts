import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
    kotlin("jvm") version "2.2.21"
    id("org.springframework.boot") version "3.5.16"
    id("dev.magicinfra.spring-evidence")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation(kotlin("reflect"))
    runtimeOnly("org.postgresql:postgresql")
}

kotlin {
    jvmToolchain(21)
}

tasks.named<BootJar>("bootJar") {
    archiveFileName.set("application.jar")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
