// Test fixture application driven by end-to-end tests (docs/06-qualite-tests-ci.md §3). Never published.
plugins {
    alias(libs.plugins.seekmind.android.application)
}

android {
    namespace = "fr.corsicajack.seekmind.fixture"

    defaultConfig {
        applicationId = "fr.corsicajack.seekmind.fixture"
        versionCode = 1
        versionName = "0.1.0"
    }
}
