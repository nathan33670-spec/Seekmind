// Variant `ads` only (ADR-0009). This module may depend on nothing but :core:common and :core:model,
// and is the only module allowed to use an online SDK (added by ticket M8-03).
plugins {
    alias(libs.plugins.seekmind.android.library)
}

dependencies {
    implementation(project(":core:model"))
}
