plugins {
    kotlin("jvm")
    id("org.springframework.boot")
    id("dev.magicinfra.spring-evidence")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(platform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES))
    implementation(project(":db-runtime"))
    runtimeOnly("org.postgresql:postgresql")
    compileOnly("com.h2database:h2")
}
