plugins {
    alias(libs.plugins.seekmind.android.library)
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

// Reference sets of testdata/ (docs/06-qualite-tests-ci.md): read by the JVM tests from the repository.
val testdata: String = rootProject.layout.projectDirectory.dir("testdata").asFile.absolutePath
tasks.withType<Test>().configureEach {
    systemProperty("seekmind.testdata", testdata)
    inputs.file("$testdata/temporal_cases.tsv")
}
