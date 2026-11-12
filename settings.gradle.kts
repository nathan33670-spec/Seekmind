pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "SeekMind"

// Modules: docs/03-architecture.md §3.1.
// `:modelpack_multi` is created by ticket M4-06; `:engine:actions` belongs to v1.1.
include(":app")

include(":core:common")
include(":core:model")
include(":core:database")
include(":core:designsystem")
include(":core:testing")

include(":engine:privacy")
include(":engine:capture")
include(":engine:ocr")
include(":engine:index")
include(":engine:embedding")
include(":engine:search")
include(":engine:source")
include(":engine:monetization")
include(":engine:ads")

include(":feature:onboarding")
include(":feature:search")
include(":feature:journal")
include(":feature:settings")
include(":feature:paywall")

include(":fixtureapp")
