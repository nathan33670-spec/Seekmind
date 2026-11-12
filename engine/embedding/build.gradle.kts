plugins {
    alias(libs.plugins.seekmind.android.library)
    // verifyModel: the bundled `fr` model must match model.lock.json (docs/specs/embeddings.md §2).
    alias(libs.plugins.seekmind.model.assets)
}
