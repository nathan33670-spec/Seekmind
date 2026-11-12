package fr.corsicajack.seekmind.core.model.port

import android.graphics.Bitmap
import fr.corsicajack.seekmind.core.common.IntRect
import fr.corsicajack.seekmind.core.model.GateDecision
import fr.corsicajack.seekmind.core.model.IngestResult
import fr.corsicajack.seekmind.core.model.OcrOutcome
import fr.corsicajack.seekmind.core.model.RawCapture
import fr.corsicajack.seekmind.core.model.Redaction
import fr.corsicajack.seekmind.core.model.ScreenMeta
import fr.corsicajack.seekmind.core.model.Shot
import fr.corsicajack.seekmind.core.model.TreeExtraction

/**
 * Privacy gate (docs/specs/confidentialite.md §2), implemented by `engine:privacy`, used by
 * `engine:capture`. Every capture goes through it before anything is stored (INV-05).
 */
interface PrivacyGate {
    /** Decision on the package alone, before any content is read. */
    fun checkPackage(meta: ScreenMeta): GateDecision

    /** Decision after the tree has been read. */
    fun checkTree(meta: ScreenMeta, extraction: TreeExtraction): GateDecision

    /** Redaction of the text before storage. Never rejects: replaces. */
    fun redact(text: String): Redaction
}

/**
 * In-memory screenshot (docs/specs/ocr.md §2), implemented by `engine:ocr`. The bitmap is valid only
 * during [block] and never leaves it (INV-03).
 */
interface ScreenshotSource {
    /**
     * Takes a screenshot of the window (API 34+) or of the screen cropped to the window (API 30-33),
     * then runs [block] with a software bitmap valid only during the call.
     */
    suspend fun <T> withScreenshot(windowId: Int, windowBounds: IntRect, block: suspend (Bitmap) -> T): Shot<T>
}

/** Text recognition of an in-memory image (docs/specs/ocr.md §4), implemented by `engine:ocr`. */
interface OcrEngine {
    suspend fun recognize(bitmap: Bitmap): OcrOutcome
}

/** Deduplication and storage of an accepted capture (docs/specs/indexation.md §2), implemented by `engine:index`. */
interface CaptureSink {
    suspend fun ingest(raw: RawCapture): IngestResult
}
