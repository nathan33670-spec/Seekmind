package fr.corsicajack.seekmind.core.model

import fr.corsicajack.seekmind.core.common.IntRect

/** Bits of [RawCapture.textSource] and [Capture.textSource] (docs/specs/stockage.md §3.1). */
object TextSource {
    const val TREE = 1
    const val OCR = 2
}

/** The screen being read (docs/specs/capture.md §5). */
data class ScreenMeta(
    val packageName: String,
    /** Qualified class name when it is an activity, otherwise null. */
    val activityName: String?,
    val windowTitle: String?,
    val windowId: Int,
    /** Epoch, ms. */
    val capturedAt: Long,
)

/** One line of text read from the accessibility tree (docs/specs/capture.md §4). */
data class TextLine(val text: String, val bounds: IntRect, val fromDescription: Boolean)

/** What the tree extractor read from one window (docs/specs/capture.md §4). */
data class TreeExtraction(
    val lines: List<TextLine>,
    val headings: List<String>,
    val hasPasswordField: Boolean,
    /** View identifiers met, for the private-browsing markers. */
    val viewIds: Set<String>,
    val nodeCount: Int,
    val truncated: Boolean,
    val textChars: Int,
    /** Cumulated area of the text lines over the window area, at most 1. */
    val textCoverage: Float,
    val windowBounds: IntRect,
    val durationMs: Long,
    /**
     * Text of the first node whose class names a browser's page (BrowserProfiles), at most 200
     * characters: the key under which the last URL read is remembered (docs/specs/capture.md §5.2).
     */
    val pageTitle: String? = null,
)

/** A capture accepted by the privacy gate, before deduplication and storage (docs/specs/capture.md §6). */
data class RawCapture(
    val meta: ScreenMeta,
    val url: String?,
    /** Lines joined by '\n', already redacted. */
    val text: String,
    /** Bits of [TextSource]. */
    val textSource: Int,
    /** Fingerprint of the tree text alone (OCR memo). */
    val treeSimHash: Long,
)

/** Why a screen was not stored (docs/specs/capture.md §6). Counted, never logged with content. */
enum class DropReason {
    CONSENT_MISSING,
    PAUSED,
    DEVICE_LOCKED,
    NO_ROOT,
    NOT_APP_WINDOW,
    APP_IGNORED,
    APP_EXCLUDED,
    APP_FAMILY_DISABLED,
    APP_SENSITIVE,
    APP_CATEGORY,
    PASSWORD_FIELD,
    PRIVATE_BROWSING,
    SECURE_WINDOW,
    TREE_TIMEOUT,
    TOO_SHORT,
    ERROR,
}

/** Events of the capture pipeline: identifiers and reasons only, no content. */
sealed interface PipelineEvent {
    data class Stored(val captureId: Long) : PipelineEvent

    data class Deduplicated(val captureId: Long) : PipelineEvent

    data class Dropped(val reason: DropReason) : PipelineEvent
}

/** State of the capture service shown to the user (docs/specs/capture.md §8). */
sealed interface CaptureStatus {
    /** Not enabled in the system accessibility settings. */
    data object ServiceDisabled : CaptureStatus

    data object ConsentMissing : CaptureStatus

    data class Paused(val until: Long?) : CaptureStatus

    data class Active(val lastCaptureAt: Long?) : CaptureStatus
}

/** A stored capture: the `capture` table of docs/specs/stockage.md §3.1, without its fingerprint. */
data class Capture(
    val id: Long,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val seenCount: Int,
    val packageName: String,
    val activityName: String?,
    val windowTitle: String?,
    val url: String?,
    /** Normalised and redacted text, lines separated by '\n'. */
    val text: String,
    /** Bits of [TextSource]. */
    val textSource: Int,
    val charCount: Int,
)

/** A line of the journal (docs/specs/stockage.md §4 `page`): enough to list, not the whole text. */
data class CaptureSummary(
    val id: Long,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val seenCount: Int,
    val packageName: String,
    val windowTitle: String?,
    val url: String?,
    /** First characters of the text, for the list. */
    val preview: String,
    val textSource: Int,
)

/** Families of applications chosen at onboarding (docs/specs/confidentialite.md §3.4). */
enum class AppFamily {
    /** Messaging and e-mail. */
    MESSAGING,

    BROWSERS,

    /** Social networks and video. */
    SOCIAL_MEDIA,

    OTHER,
}
