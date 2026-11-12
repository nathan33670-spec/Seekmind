plugins {
    `kotlin-dsl`
}

group = "fr.corsicajack.seekmind.buildlogic"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // compileOnly: the plugins themselves are put on the build classpath by the root build script,
    // so that every project resolves exactly one version of each.
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.compose.gradlePlugin)
    compileOnly(libs.ksp.gradlePlugin)
    compileOnly(libs.hilt.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "seekmind.android.application"
            implementationClass = "fr.corsicajack.seekmind.buildlogic.AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "seekmind.android.library"
            implementationClass = "fr.corsicajack.seekmind.buildlogic.AndroidLibraryConventionPlugin"
        }
        register("androidCompose") {
            id = "seekmind.android.compose"
            implementationClass = "fr.corsicajack.seekmind.buildlogic.AndroidComposeConventionPlugin"
        }
        register("kotlinLibrary") {
            id = "seekmind.kotlin.library"
            implementationClass = "fr.corsicajack.seekmind.buildlogic.KotlinLibraryConventionPlugin"
        }
        register("hilt") {
            id = "seekmind.hilt"
            implementationClass = "fr.corsicajack.seekmind.buildlogic.HiltConventionPlugin"
        }
        register("androidTest") {
            id = "seekmind.android.test"
            implementationClass = "fr.corsicajack.seekmind.buildlogic.AndroidTestConventionPlugin"
        }
    }
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
