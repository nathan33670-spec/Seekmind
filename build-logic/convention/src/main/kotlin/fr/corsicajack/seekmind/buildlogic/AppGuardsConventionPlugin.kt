package fr.corsicajack.seekmind.buildlogic

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import fr.corsicajack.seekmind.buildlogic.guards.VerifyDependenciesTask
import fr.corsicajack.seekmind.buildlogic.guards.VerifyManifestTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register

/**
 * Guards of the shipped application (ticket M0-04), registered for each release variant:
 * `verify<Variant>Manifest` and `verify<Variant>Dependencies`, aggregated by `verifyDependencies`.
 * Allowlists live in `gradle/` at the repository root.
 */
class AppGuardsConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        val gradleDir = rootProject.layout.projectDirectory.dir("gradle")
        val verifyDependencies = tasks.register("verifyDependencies") {
            group = "verification"
            description = "Verifies the runtime dependencies of every release variant against their allowlist."
        }

        extensions.getByType<ApplicationAndroidComponentsExtension>().onVariants { variant ->
            if (variant.buildType != "release") return@onVariants
            val flavor = variant.flavorName.orEmpty()
            val suffix = variant.name.replaceFirstChar(Char::uppercaseChar)

            tasks.register<VerifyManifestTask>("verify${suffix}Manifest") {
                group = "verification"
                description =
                    "Verifies permissions, exported components and backup flag of the ${variant.name} manifest."
                mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
                permissionAllowlist.set(gradleDir.file("permission-allowlist-$flavor.txt"))
                exportedAllowlist.set(gradleDir.file("exported-allowlist.txt"))
                accessibilityConfigs.from(
                    rootProject.fileTree(rootProject.projectDir) {
                        include("**/src/main/res/xml/accessibility_service_config.xml")
                        exclude("**/build/**")
                    },
                )
                applicationId.set(variant.applicationId)
                report.set(layout.buildDirectory.file("reports/guards/manifest-${variant.name}.txt"))
            }

            val dependencies = tasks.register<VerifyDependenciesTask>("verify${suffix}Dependencies") {
                group = "verification"
                description = "Verifies the runtime dependencies of ${variant.name} against its allowlist."
                rootComponent.set(variant.runtimeConfiguration.incoming.resolutionResult.rootComponent)
                allowlist.set(gradleDir.file("dependency-allowlist-$flavor.txt"))
                report.set(layout.buildDirectory.file("reports/guards/dependencies-${variant.name}.txt"))
            }
            verifyDependencies.configure { dependsOn(dependencies) }
        }
    }
}
