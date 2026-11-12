plugins {
    alias(libs.plugins.seekmind.android.library)
}

// Test doubles and data sets (docs/06-qualite-tests-ci.md §2), for test configurations only
// (verifyModuleGraph).
dependencies {
    api(project(":core:common"))
    api(project(":core:model"))
    api(project(":core:database"))
    api(libs.kotlinx.coroutines.test)

    testImplementation(libs.junit)
    testImplementation(libs.truth)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.truth)
}
