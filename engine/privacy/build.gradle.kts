plugins {
    alias(libs.plugins.seekmind.android.library)
}

android {
    testOptions {
        unitTests.all {
            // Reference sets of docs/06-qualite-tests-ci.md, read by the JVM tests.
            it.systemProperty("seekmind.testdata", rootProject.file("testdata").path)
        }
    }
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
}
