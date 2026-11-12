package fr.corsicajack.seekmind.buildlogic

import com.android.build.api.dsl.Lint
import org.gradle.api.Project
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

/**
 * Lint settings shared by every Android module (ticket M0-02): no baseline, abort on errors,
 * untranslated strings and hard-coded UI text are errors.
 */
internal fun Lint.configureSeekMindLint() {
    abortOnError = true
    checkReleaseBuilds = true
    error += setOf("MissingTranslation", "HardcodedText", "ExtraTranslation")
    // Version-check lints query the network and belong to dependency-update PRs, not to every build.
    disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
}

/** Kotlin warnings are errors in main sources; test sources keep them as warnings. */
internal fun Project.configureKotlinWarningsAsErrors() {
    tasks.withType<KotlinCompilationTask<*>>().configureEach {
        if (!name.contains("Test")) {
            compilerOptions.allWarningsAsErrors.set(true)
        }
    }
}
