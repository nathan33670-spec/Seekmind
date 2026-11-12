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

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.truth)
    // SettingsRepository's writes return DataStore preferences.
    androidTestImplementation(libs.androidx.datastore.preferences)
}
