package fr.corsicajack.seekmind.core.model

/** How precisely SeekMind can go back to the source of a capture (docs/specs/retour-source.md §1). */
enum class SourceLevel {
    /** The original page. */
    EXACT_URL,

    /** A precise screen of the application, deduced from the content. */
    APP_LINK,

    /** A search in the application, prefilled. */
    APP_SEARCH,

    /** The application, on its home screen. */
    APP_ONLY,

    /** The application is uninstalled. */
    UNAVAILABLE,
}

data class SourceRef(
    val level: SourceLevel,
    /** `Intent.toUri(Intent.URI_INTENT_SCHEME)`, null when [SourceLevel.UNAVAILABLE]. */
    val intentUri: String?,
    val appLabel: String?,
)
