plugins {
    alias(libs.plugins.seekmind.android.library)
}

dependencies {
    implementation(project(":core:model"))

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
