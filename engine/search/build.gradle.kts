plugins {
    alias(libs.plugins.seekmind.android.library)
}

android {
    androidResources {
        // The fr model of engine:embedding reaches the test APK of the evaluation: kept uncompressed.
        noCompress += "onnx"
    }
    // The reference set of the search evaluation (docs/specs/recherche.md §11): corpus.jsonl, queries.jsonl.
    sourceSets {
        getByName("androidTest").assets.directories.add(rootProject.file("testdata/eval").path)
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:database"))
    // VectorCodec: the binary and int8 forms of the vectors (docs/specs/embeddings.md §6).
    implementation(project(":engine:embedding"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(project(":core:testing"))
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(project(":core:testing"))
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.kotlinx.coroutines.core)
}

// Reference sets of testdata/ (docs/06-qualite-tests-ci.md): read by the JVM tests from the repository.
val testdata: String = rootProject.layout.projectDirectory.dir("testdata").asFile.absolutePath
tasks.withType<Test>().configureEach {
    systemProperty("seekmind.testdata", testdata)
    inputs.file("$testdata/temporal_cases.tsv")
}
