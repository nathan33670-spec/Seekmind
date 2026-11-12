package fr.corsicajack.seekmind.buildlogic.guards

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

private fun DefaultTask.finish(
    report: RegularFileProperty,
    title: String,
    violations: List<String>,
    details: List<String> = emptyList(),
) {
    report.get().asFile.writeText((violations + details).joinToString("\n"))
    if (violations.isNotEmpty()) throw GradleException("$title:\n" + violations.joinToString("\n") { "  - $it" })
    logger.lifecycle("$title: OK")
}

/** Verifies the merged manifest of one release variant (`:app:verifyAdsReleaseManifest`, …). */
@DisableCachingByDefault(because = "Cheap check whose value is its failure output")
abstract class VerifyManifestTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val permissionAllowlist: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val exportedAllowlist: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val accessibilityConfigs: ConfigurableFileCollection

    @get:Input
    abstract val applicationId: Property<String>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        val violations = ManifestRules.checkManifest(
            manifestXml = mergedManifest.get().asFile.readText(),
            applicationId = applicationId.get(),
            allowedPermissions = parseAllowlist(permissionAllowlist.get().asFile.readText()),
            exportedAllowlist = parseAllowlist(exportedAllowlist.get().asFile.readText()),
        ) +
            accessibilityConfigs.files.sorted().flatMap {
                ManifestRules.checkAccessibilityConfig(it.path, it.readText())
            }
        finish(report, "Manifest of $name", violations)
    }
}

/** Verifies the external runtime dependencies of one release variant against its allowlist. */
@DisableCachingByDefault(because = "Cheap check whose value is its failure output")
abstract class VerifyDependenciesTask : DefaultTask() {
    @get:Input
    abstract val rootComponent: Property<ResolvedComponentResult>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val allowlist: RegularFileProperty

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        val modules = sortedSetOf<String>()
        val seen = mutableSetOf<ResolvedComponentResult>()
        val queue = ArrayDeque(listOf(rootComponent.get()))
        while (queue.isNotEmpty()) {
            val component = queue.removeFirst()
            if (!seen.add(component)) continue
            (component.id as? ModuleComponentIdentifier)?.let { modules += "${it.group}:${it.module}" }
            component.dependencies.filterIsInstance<ResolvedDependencyResult>().forEach { queue += it.selected }
        }
        val violations = DependencyRules.violations(modules, parseAllowlist(allowlist.get().asFile.readText()))
        finish(report, "Dependencies of $name", violations, listOf("", "Resolved modules:") + modules)
    }
}

/** Verifies the module graph of docs/03-architecture.md §3.2 (`:verifyModuleGraph`). */
@DisableCachingByDefault(because = "Cheap check whose value is its failure output")
abstract class VerifyModuleGraphTask : DefaultTask() {
    @get:Input
    abstract val edges: ListProperty<String>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        val parsed = edges.get().map(ModuleEdge::parse)
        finish(report, "Module graph", ModuleGraphRules.violations(parsed), listOf("", "Edges:") + edges.get())
    }
}
