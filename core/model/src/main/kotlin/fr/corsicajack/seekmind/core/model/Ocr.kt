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
