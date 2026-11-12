plugins {
    alias(libs.plugins.seekmind.android.library)
}

dependencies {
    implementation(project(":core:model"))

    testImplementation(project(":core:testing"))
    testImplementation(libs.junit)
    testImplementation(libs.truth)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.truth)
}
