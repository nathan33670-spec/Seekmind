package fr.corsicajack.seekmind.buildlogic

import fr.corsicajack.seekmind.buildlogic.guards.ModuleEdge
import fr.corsicajack.seekmind.buildlogic.guards.VerifyModuleGraphTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.kotlin.dsl.register

/** Root project conventions: `verifyModuleGraph` (ticket M0-04, architecture §3.2). */
class RootConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        check(this == rootProject) { "seekmind.root must be applied to the root project" }
        tasks.register<VerifyModuleGraphTask>("verifyModuleGraph") {
            group = "verification"
            description = "Verifies that project dependencies follow the allowed module graph."
            // Evaluated when the task graph is stored, once every project has been configured.
            edges.set(
                provider {
                    subprojects.flatMap { project ->
                        project.configurations.flatMap { configuration ->
                            configuration.dependencies.withType(ProjectDependency::class.java)
                                .map { ModuleEdge(project.path, configuration.name, it.path).serialize() }
                        }
                    }.distinct().sorted()
                },
            )
            report.set(layout.buildDirectory.file("reports/guards/module-graph.txt"))
        }
    }
}
