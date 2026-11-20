package fr.corsicajack.seekmind.buildlogic

import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType

/** Root package of the application; each module's namespace is derived from its Gradle path. */
internal const val ROOT_PACKAGE = "fr.corsicajack.seekmind"

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.intVersion(alias: String): Int = findVersion(alias).get().requiredVersion.toInt()

internal val Project.javaVersion: JavaVersion
    get() = JavaVersion.toVersion(libs.findVersion("jvmTarget").get().requiredVersion)

/** `:engine:capture` → `fr.corsicajack.seekmind.engine.capture`. */
internal val Project.derivedNamespace: String
    get() = ROOT_PACKAGE + path.replace(':', '.').replace('-', '_')

/**
 * Some JVM tests read files of `src/main` directly (rule tables, word lists, resources): those files are
 * inputs of the tests, or a change to them would be met with a stale result from the build cache.
 */
internal fun Project.declareMainSourcesAsTestInputs() {
    tasks.withType<Test>().configureEach {
        inputs.files(layout.projectDirectory.dir("src/main").asFileTree)
            .withPropertyName("mainSources")
            .withPathSensitivity(PathSensitivity.RELATIVE)
    }
}
