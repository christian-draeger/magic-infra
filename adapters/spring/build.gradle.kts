plugins {
    kotlin("jvm")
    application
    `java-gradle-plugin`
}

dependencies {
    implementation("com.google.code.gson:gson:2.14.0")
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
