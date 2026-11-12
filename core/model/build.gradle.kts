plugins {
    alias(libs.plugins.seekmind.android.library)
}

dependencies {
    // IntRect, Outcome and kotlinx-coroutines (StateFlow, Flow) are part of the API of the domain.
    api(project(":core:common"))

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
