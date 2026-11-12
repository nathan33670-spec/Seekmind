package fr.corsicajack.seekmind.engine.ocr

import android.graphics.Bitmap
import fr.corsicajack.seekmind.core.model.IntRect

/** Text recognition of an in-memory image (docs/specs/ocr.md §4). The bitmap is never kept. */
interface OcrEngine {
    suspend fun recognize(bitmap: Bitmap): OcrOutcome

    /** Releases the native resources. The engine is unusable afterwards. */
    fun close()
}

data class OcrOutcome(val lines: List<OcrLine>, val durationMs: Long)

data class OcrLine(val text: String, val bounds: IntRect, val confidence: Float?)
