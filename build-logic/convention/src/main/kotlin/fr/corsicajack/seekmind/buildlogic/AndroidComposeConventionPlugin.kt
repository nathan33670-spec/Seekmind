package fr.corsicajack.seekmind.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.register

/** Enables Jetpack Compose on an Android application or library module. Apply after the Android plugin. */
class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

        pluginManager.withPlugin("com.android.application") {
            extensions.configure<ApplicationExtension> { buildFeatures.compose = true }
        }
        pluginManager.withPlugin("com.android.library") {
            extensions.configure<LibraryExtension> { buildFeatures.compose = true }
        }

        val hardcodedUiText = tasks.register<HardcodedUiTextTask>("verifyNoHardcodedUiText") {
            group = "verification"
            description = "Fails when Compose code hard-codes user-visible text."
            sources.from(fileTree("src") { include("main/**/*.kt", "ads/**/*.kt", "offline/**/*.kt") })
            report.set(layout.buildDirectory.file("reports/hardcoded-ui-text.txt"))
        }
        tasks.matching { it.name == "lint" || it.name == "check" }.configureEach { dependsOn(hardcodedUiText) }

        dependencies {
            val bom = platform(libs.findLibrary("androidx-compose-bom").get())
            add("implementation", bom)
            add("androidTestImplementation", bom)
            add("implementation", libs.findLibrary("androidx-compose-ui").get())
            add("implementation", libs.findLibrary("androidx-compose-ui-tooling-preview").get())
            add("implementation", libs.findLibrary("androidx-compose-material3").get())
            add("debugImplementation", libs.findLibrary("androidx-compose-ui-tooling").get())
        }
    }
}
