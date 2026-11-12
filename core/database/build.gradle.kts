plugins {
    alias(libs.plugins.seekmind.android.library)
    alias(libs.plugins.ksp)
}

dependencies {
    // Prototype S1 (ticket M0-06): Room on SQLCipher with FTS5, in the instrumented tests only.
    // The production stack arrives with the M1 tickets.
    androidTestImplementation(libs.androidx.room.runtime)
    androidTestImplementation(libs.androidx.sqlite)
    androidTestImplementation(libs.sqlcipher.android)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.truth)
    kspAndroidTest(libs.androidx.room.compiler)
}
