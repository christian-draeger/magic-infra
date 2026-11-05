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
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import java.nio.file.Files

private const val RESOURCE_ROOT = "src/main/resources"

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
                it.projectDirectory.set(project.layout.projectDirectory)
                it.bytecode.set(project.providers.gradleProperty("magicInfra.bytecode").map { value ->
                    value.toBooleanStrictOrNull()
                        ?: throw GradleException("MI_SPRING_BYTECODE_OPTION: Set -PmagicInfra.bytecode to true or false.")
                }.orElse(false))
                if (it.bytecode.get()) {
                    val classes = project.extensions.getByType(SourceSetContainer::class.java)
                        .named(SourceSet.MAIN_SOURCE_SET_NAME).map { source -> source.output.classesDirs }
                    it.dependsOn(classes)
                    it.classFiles.from(classes.map { outputs ->
                        outputs.asFileTree.matching { pattern -> pattern.include("**/*.class") }
                    })
                }
                it.output.set(project.layout.buildDirectory.file("magic-infra/evidence.json"))
            }
            project.pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
                task.configure { it.language.set("kotlin") }
            }
        }
    }
}

@UntrackedTask(because = "Analysis walks and validates its own bounded snapshot, including symbolic links.")
abstract class SpringEvidenceTask : DefaultTask() {
    @get:Input abstract val applicationId: Property<String>
    @get:Input abstract val module: Property<String>
    @get:Input abstract val language: Property<String>
    @get:Input abstract val profiles: ListProperty<String>
    @get:Input abstract val coordinates: ListProperty<String>
    @get:Internal abstract val projectDirectory: DirectoryProperty
    @get:Input abstract val bytecode: Property<Boolean>
    @get:InputFiles abstract val classFiles: ConfigurableFileCollection
    @get:OutputFile abstract val output: RegularFileProperty

    @TaskAction
    fun collect() {
        val request = AnalysisRequest(applicationId.get(), module.get(), language.get(), profiles.get(),
            listOf(RESOURCE_ROOT), coordinates.get())
        val document = try {
            evidence(request, projectDirectory.get().asFile.toPath(), classFiles.files.takeIf { bytecode.get() })
        } catch (failure: ProtocolFailure) {
            val hint = if (failure.code == "MI_SPRING_APPLICATION_ID") {
                " Use -PmagicInfra.applicationId=shop to override the module name."
            } else ""
            throw GradleException("${failure.code}: ${failure.message}$hint")
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
