plugins {
    alias(libs.plugins.seekmind.android.library)
    alias(libs.plugins.seekmind.hilt)
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:database"))

    testImplementation(project(":core:testing"))
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    // A real SettingsRepository on a temporary file.
    testImplementation(libs.androidx.datastore.preferences)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.truth)
}
