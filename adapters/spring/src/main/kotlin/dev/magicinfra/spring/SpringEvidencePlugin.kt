package dev.magicinfra.spring

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.DependencyResult
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.ResolvedVariantResult
import org.gradle.api.artifacts.result.UnresolvedDependencyResult
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.nio.file.Files

class SpringEvidencePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.withPlugin("org.springframework.boot") {
            val task = project.tasks.register("springEvidence", SpringEvidenceTask::class.java) {
                it.group = "verification"
                it.description = "Collects magic-infra Evidence from the resolved Spring Boot runtime graph."
                it.applicationId.set(project.providers.gradleProperty("magicInfra.applicationId").orElse(project.name))
                it.module.set(project.path)
                it.language.set("java")
                it.profiles.set(project.providers.gradleProperty("magicInfra.profiles").map { profiles ->
                    profiles.split(',').map(String::trim).filter(String::isNotEmpty)
                }.orElse(emptyList()))
                it.coordinates.set(project.configurations.getByName("runtimeClasspath")
                    .incoming.resolutionResult.rootComponent.map(::resolvedCoordinates))
                it.output.set(project.layout.buildDirectory.file("magic-infra/evidence.json"))
            }
            project.pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
                task.configure { it.language.set("kotlin") }
            }
        }
    }
}

@CacheableTask
abstract class SpringEvidenceTask : DefaultTask() {
    @get:Input abstract val applicationId: Property<String>
    @get:Input abstract val module: Property<String>
    @get:Input abstract val language: Property<String>
    @get:Input abstract val profiles: ListProperty<String>
    @get:Input abstract val coordinates: ListProperty<String>
    @get:OutputFile abstract val output: RegularFileProperty

    @TaskAction
    fun collect() {
        val request = AnalysisRequest(applicationId.get(), module.get(), language.get(), profiles.get(),
            listOf("src/main/resources"), coordinates.get())
        val document = try {
            evidence(request)
        } catch (failure: ProtocolFailure) {
            throw GradleException("${failure.code}: ${failure.message}")
        }
        val path = output.get().asFile.toPath()
        Files.createDirectories(path.parent)
        Files.writeString(path, document)
    }
}

private fun resolvedCoordinates(root: ResolvedComponentResult): List<String> {
    val pending = ArrayDeque<DependencyResult>()
    pending.addAll(root.dependencies)
    val seen = mutableSetOf<Pair<ComponentIdentifier, ResolvedVariantResult>>()
    val coordinates = sortedSetOf<String>()
    while (pending.isNotEmpty()) {
        val edge = pending.removeFirst()
        if (edge.isConstraint) continue
        when (edge) {
            is UnresolvedDependencyResult -> throw GradleException(
                "MI_SPRING_RUNTIME_UNRESOLVED: Resolve the module's runtimeClasspath before collecting Evidence."
            )
            is ResolvedDependencyResult -> {
                val component = edge.selected
                val variant = edge.resolvedVariant
                if (!seen.add(component.id to variant)) continue
                val id = component.id as? ModuleComponentIdentifier
                if (id != null && dependencyOfInterest(id.group, id.module)) {
                    coordinates.add("${id.group}:${id.module}:${id.version}")
                }
                pending.addAll(component.getDependenciesForVariant(variant))
            }
        }
    }
    return coordinates.toList()
}
