plugins {
    kotlin("jvm")
    application
    `java-library`
}

dependencies {
    api("com.fasterxml.jackson.core:jackson-databind:2.22.1")
    implementation("com.networknt:json-schema-validator:2.0.8")
    implementation("org.yaml:snakeyaml:2.6")
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
    from(rootProject.file("schemas/infra.v1alpha2.schema.json"))
    from(rootProject.file("schemas/application.v1alpha1.schema.json"))
    from(rootProject.file("schemas/plan.v1alpha1.schema.json"))
}
