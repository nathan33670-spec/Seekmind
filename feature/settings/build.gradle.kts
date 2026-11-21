plugins {
    alias(libs.plugins.seekmind.android.library)
    alias(libs.plugins.seekmind.android.compose)
}

dependencies {
    implementation(project(":core:database"))
    implementation(project(":core:model"))
    // The activation by code (docs/specs/monetisation.md §5.4).
    implementation(project(":engine:monetization"))
    // The effective state of an application and its origin (docs/specs/confidentialite.md §3.3).
    implementation(project(":engine:privacy"))
    implementation(project(":core:designsystem"))
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    // The file picker of the pack import.
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
