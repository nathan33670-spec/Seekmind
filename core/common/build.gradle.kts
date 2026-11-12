plugins {
    alias(libs.plugins.seekmind.kotlin.library)
}

dependencies {
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
