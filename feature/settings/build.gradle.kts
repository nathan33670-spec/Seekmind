plugins {
    alias(libs.plugins.seekmind.android.library)
    alias(libs.plugins.seekmind.android.compose)
}

dependencies {
    implementation(project(":core:designsystem"))

    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4.accessibility)
    androidTestImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.truth)
}
