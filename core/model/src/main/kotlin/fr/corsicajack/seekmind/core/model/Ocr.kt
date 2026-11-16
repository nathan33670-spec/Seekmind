package fr.corsicajack.seekmind.core.model

import fr.corsicajack.seekmind.core.common.IntRect

/** Text recognised in an image (docs/specs/ocr.md §4). */
data class OcrOutcome(val lines: List<OcrLine>, val durationMs: Long)

data class OcrLine(val text: String, val bounds: IntRect, val confidence: Float?)

/** Result of an in-memory screenshot (docs/specs/ocr.md §2). */
sealed interface Shot<out T> {
    data class Ok<T>(val value: T) : Shot<T>

    data object SecureWindow : Shot<Nothing>

    data object RateLimited : Shot<Nothing>

    data class Failed(val code: Int) : Shot<Nothing>
}

/** Result of the secure-window probe (docs/specs/ocr.md §3). */
sealed interface ProbeResult {
    data object Secure : ProbeResult

    /** [ocr]: the text recognised on the same screenshot, when it was asked for. */
    data class NotSecure(val ocr: OcrOutcome?) : ProbeResult

    /** The policy needs no probe for this screen. */
    data object Skipped : ProbeResult

    /** The screenshot failed (rate limit, internal error). */
    data object Unknown : ProbeResult
}
