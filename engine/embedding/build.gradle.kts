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

// Tokenizer parity tests (docs/specs/embeddings.md §3.3): the reference files of testdata/ and the
// vocabularies built by tools/model (never committed). Without a vocabulary, the parity tests are
// skipped locally and fail on CI.
tasks.withType<Test>().configureEach {
    val testdata = rootProject.file("testdata")
    val modelOut = rootProject.file("tools/model/out")
    val bundled = file("src/main/assets/model")
    systemProperty("seekmind.testdata", testdata.path)
    systemProperty("seekmind.modelOut", modelOut.path)
    systemProperty("seekmind.bundledModel", bundled.path)
    inputs.dir(testdata).withPropertyName("testdata").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(fileTree(modelOut) { include("*/vocab.bin") }).withPropertyName("vocabularies")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(fileTree(bundled) { include("*/vocab.bin") }).withPropertyName("bundledVocabularies")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("ci", providers.environmentVariable("CI").orElse("false"))
}

dependencies {
    implementation(libs.onnxruntime.android)

    testImplementation(libs.junit)
    testImplementation(libs.truth)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.truth)
}
