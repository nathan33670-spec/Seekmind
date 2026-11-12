package fr.corsicajack.seekmind.buildlogic

import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType

/** Root package of the application; each module's namespace is derived from its Gradle path. */
internal const val ROOT_PACKAGE = "fr.corsicajack.seekmind"

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.intVersion(alias: String): Int =
    findVersion(alias).get().requiredVersion.toInt()

internal val Project.javaVersion: JavaVersion
    get() = JavaVersion.toVersion(libs.findVersion("jvmTarget").get().requiredVersion)

/** `:engine:capture` → `fr.corsicajack.seekmind.engine.capture`. */
internal val Project.derivedNamespace: String
    get() = ROOT_PACKAGE + path.replace(':', '.').replace('-', '_')
