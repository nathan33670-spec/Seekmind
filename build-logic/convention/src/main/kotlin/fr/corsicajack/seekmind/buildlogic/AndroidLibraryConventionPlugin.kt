package fr.corsicajack.seekmind.buildlogic

import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/** Common configuration of Android library modules (`core:*` except `core:common`, `engine:*`, `feature:*`). */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")

        extensions.configure<LibraryExtension> {
            namespace = derivedNamespace
            compileSdk = libs.intVersion("compileSdk")
            defaultConfig {
                minSdk = libs.intVersion("minSdk")
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                consumerProguardFiles("consumer-rules.pro")
            }
            compileOptions {
                sourceCompatibility = javaVersion
                targetCompatibility = javaVersion
            }
            lint {
                targetSdk = libs.intVersion("targetSdk")
                configureSeekMindLint()
            }
            testOptions {
                targetSdk = libs.intVersion("targetSdk")
            }
        }
        configureKotlinWarningsAsErrors()
    }
}
