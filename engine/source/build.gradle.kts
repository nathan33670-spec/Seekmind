plugins {
    alias(libs.plugins.seekmind.android.library)
}

dependencies {
    implementation(project(":core:model"))
    // source_rules.json (docs/specs/retour-source.md §4).
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    // Intents and the PackageManager are built and read on the JVM (docs/06-qualite-tests-ci.md §2).
    testImplementation(libs.robolectric)
}
