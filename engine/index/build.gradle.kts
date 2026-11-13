plugins {
    alias(libs.plugins.seekmind.android.library)
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:database"))

    testImplementation(project(":core:testing"))
    testImplementation(libs.junit)
    testImplementation(libs.truth)

    androidTestImplementation(project(":core:testing"))
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.truth)
}
