plugins {
    alias(libs.plugins.seekmind.android.library)
    alias(libs.plugins.seekmind.android.compose)
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:database"))
    implementation(project(":core:designsystem"))
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.datastore.preferences)

    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4.accessibility)
    androidTestImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.truth)
}

// DisclosureTextTest compares the disclosure resources with the published text: an input of the tests.
tasks.withType<Test>().configureEach {
    inputs.file(rootProject.file("docs/05-conformite-play-store.md")).withPropertyName("disclosureText")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
