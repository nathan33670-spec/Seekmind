plugins {
    alias(libs.plugins.seekmind.android.library)
}

dependencies {
    api(project(":core:model"))
    // The cache of the secure-window probe (`secure_probe`, docs/specs/ocr.md §3.2).
    implementation(project(":core:database"))
    implementation(libs.kotlinx.coroutines.core)
    // Bundled model only: the variant that downloads its model is forbidden (docs/specs/ocr.md §4).
    implementation(libs.mlkit.text.recognition)

    testImplementation(project(":core:testing"))
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
