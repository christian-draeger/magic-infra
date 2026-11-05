plugins {
    kotlin("jvm")
    application
    `java-gradle-plugin`
}

dependencies {
    implementation("com.google.code.gson:gson:2.14.0")
    implementation(platform("org.springframework.boot:spring-boot-dependencies:3.5.16"))
    implementation("org.springframework.boot:spring-boot")
    implementation("org.yaml:snakeyaml")
    implementation("org.ow2.asm:asm:9.9.1")
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("dev.magicinfra.spring.AdapterMainKt")
}

gradlePlugin {
    plugins {
        create("springEvidence") {
            id = "dev.magicinfra.spring-evidence"
            implementationClass = "dev.magicinfra.spring.SpringEvidencePlugin"
        }
    }
}
