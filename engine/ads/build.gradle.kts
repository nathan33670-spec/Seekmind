// Variant `ads` only (ADR-0009). This module may depend on nothing but :core:common and :core:model,
// and is the only module allowed to use an online SDK (docs/specs/monetisation.md §3.3).
plugins {
    alias(libs.plugins.seekmind.android.library)
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(libs.play.services.ads)
    implementation(libs.user.messaging.platform)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}
