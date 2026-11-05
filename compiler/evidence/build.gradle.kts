plugins {
    kotlin("jvm")
    application
    `java-library`
}

dependencies {
    api("com.fasterxml.jackson.core:jackson-databind:2.22.1")
    implementation("com.networknt:json-schema-validator:2.0.8")
    runtimeOnly("org.slf4j:slf4j-nop:2.0.17")
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("dev.magicinfra.compiler.evidence.EvidenceMainKt")
}

tasks.processResources {
    from(rootProject.file("schemas/evidence.v1alpha1.schema.json"))
}
