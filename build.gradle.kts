// Puts every Gradle plugin on the build classpath once, with the versions of the catalog.
// Modules apply them through the convention plugins of build-logic.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.spotless)
}

// Formatting of every Kotlin source and Gradle script of the repository (ticket M0-02).
// ktlint reads its rules from the root .editorconfig.
spotless {
    val ktlintVersion = libs.versions.ktlint.get()
    val editorConfig = rootProject.file(".editorconfig")
    kotlin {
        target("**/*.kt")
        targetExclude("**/build/**", "**/.gradle/**")
        ktlint(ktlintVersion).setEditorConfigPath(editorConfig)
    }
    kotlinGradle {
        target("**/*.gradle.kts")
        targetExclude("**/build/**", "**/.gradle/**")
        ktlint(ktlintVersion).setEditorConfigPath(editorConfig)
    }
}
