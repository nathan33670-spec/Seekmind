plugins {
    alias(libs.plugins.seekmind.android.application)
    alias(libs.plugins.seekmind.android.compose)
    alias(libs.plugins.seekmind.hilt)
    alias(libs.plugins.seekmind.app.guards)
}

// AdMob application id (docs/04-prerequis.md §3). Google's public sample id is used unless the
// `seekmind.admob.appId` property is provided; ticket M11-05 makes it mandatory for release builds.
val admobAppId: String = providers.gradleProperty("seekmind.admob.appId")
    .getOrElse("ca-app-pub-3940256099942544~3347511713")

android {
    namespace = "fr.corsicajack.seekmind"

    defaultConfig {
        applicationId = "fr.corsicajack.seekmind"
        versionCode = 1
        versionName = "0.1.0"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // Two variants of the same code (ADR-0009). Variant-specific code lives only in src/ads and src/offline.
    flavorDimensions += "network"
    productFlavors {
        create("ads") {
            dimension = "network"
            manifestPlaceholders["admobAppId"] = admobAppId
        }
        create("offline") {
            dimension = "network"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            ndk {
                abiFilters += "x86_64" // CI emulators
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    androidResources {
        // Embedding models are memory-mapped from the APK: they must stay uncompressed.
        noCompress += "onnx"
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":core:database"))
    implementation(project(":core:designsystem"))

    implementation(project(":engine:privacy"))
    implementation(project(":engine:capture"))
    implementation(project(":engine:ocr"))
    implementation(project(":engine:index"))
    implementation(project(":engine:embedding"))
    implementation(project(":engine:search"))
    implementation(project(":engine:source"))
    implementation(project(":engine:monetization"))
    "adsImplementation"(project(":engine:ads"))

    implementation(project(":feature:onboarding"))
    implementation(project(":feature:search"))
    implementation(project(":feature:journal"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:paywall"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
}
