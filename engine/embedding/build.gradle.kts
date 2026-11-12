plugins {
    alias(libs.plugins.seekmind.android.library)
    // verifyModel: the bundled `fr` model must match model.lock.json (docs/specs/embeddings.md §2).
    alias(libs.plugins.seekmind.model.assets)
}

android {
    androidResources {
        // The model is memory-mapped from the APK, test APK included: it must stay uncompressed.
        noCompress += "onnx"
    }
    sourceSets {
        // Reference vectors of tools/model (prototype S4, then the engine tests of M4).
        getByName("androidTest").assets.directories.add(rootProject.file("testdata").path)
    }
}

dependencies {
    implementation(libs.onnxruntime.android)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.truth)
}
