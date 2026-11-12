plugins {
    alias(libs.plugins.seekmind.android.library)
}

dependencies {
    api(project(":core:model"))
    implementation(libs.kotlinx.coroutines.core)
    // Bundled model only: the variant that downloads its model is forbidden (docs/specs/ocr.md §4).
    implementation(libs.mlkit.text.recognition)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
