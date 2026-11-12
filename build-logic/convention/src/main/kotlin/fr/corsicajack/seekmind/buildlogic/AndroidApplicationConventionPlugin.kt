package fr.corsicajack.seekmind.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * Common configuration of Android applications (`:app`, `:fixtureapp`).
 * Kotlin is built into AGP 9: the `kotlin-android` plugin must not be applied.
 */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.application")

        extensions.configure<ApplicationExtension> {
            compileSdk = libs.intVersion("compileSdk")
            buildToolsVersion = libs.findVersion("buildTools").get().requiredVersion
            defaultConfig {
                minSdk = libs.intVersion("minSdk")
                targetSdk = libs.intVersion("targetSdk")
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            }
            compileOptions {
                sourceCompatibility = javaVersion
                targetCompatibility = javaVersion
            }
            lint { configureSeekMindLint() }
        }
        configureKotlinWarningsAsErrors()
    }
}
