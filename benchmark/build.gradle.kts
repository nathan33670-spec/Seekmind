plugins {
    alias(libs.plugins.seekmind.android.library)
}

// Microbenchmarks of the data layer (ticket M1-07, docs/06-qualite-tests-ci.md §6): ENF-PERF-03 and
// ENF-PERF-05. Results: build/outputs/connected_android_test_additional_output/ (JSON).
android {
    defaultConfig {
        testInstrumentationRunner = "androidx.benchmark.junit4.AndroidBenchmarkRunner"
        // CI emulators with a debuggable test APK, which cannot be compiled ahead of time
        // (NOT-AOT-COMPILED follows from DEBUGGABLE): the figures are indicative, the phone gives the
        // reference ones.
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] =
            "EMULATOR,DEBUGGABLE,NOT-AOT-COMPILED,LOW-BATTERY,UNLOCKED,ENG-BUILD"
    }
}

dependencies {
    androidTestImplementation(project(":core:testing"))
    androidTestImplementation(libs.androidx.benchmark.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.truth)
}
