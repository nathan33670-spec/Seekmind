plugins {
    alias(libs.plugins.seekmind.android.library)
}

dependencies {
    implementation(project(":core:model"))

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}

// Reference sets of testdata/ (docs/06-qualite-tests-ci.md): read by the JVM tests from the repository.
val testdata: String = rootProject.layout.projectDirectory.dir("testdata").asFile.absolutePath
tasks.withType<Test>().configureEach {
    systemProperty("seekmind.testdata", testdata)
    inputs.file("$testdata/temporal_cases.tsv")
}
