plugins {
    alias(libs.plugins.seekmind.android.library)
    alias(libs.plugins.ksp)
}

android {
    sourceSets {
        // Exported schemas, for MigrationTestHelper; the spec, for SchemaConformanceTest.
        getByName("androidTest").assets.directories.add(file("schemas").path)
        getByName("androidTest").assets.directories.add(rootProject.file("docs/specs").path)
    }
}

ksp {
    // docs/specs/stockage.md §7: exported schemas are versioned in core/database/schemas/.
    arg("room.schemaLocation", file("schemas").path)
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(project(":core:model"))
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.sqlite)
    implementation(libs.sqlcipher.android)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.truth)

    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.turbine)
    androidTestImplementation(libs.androidx.work.testing)
}
