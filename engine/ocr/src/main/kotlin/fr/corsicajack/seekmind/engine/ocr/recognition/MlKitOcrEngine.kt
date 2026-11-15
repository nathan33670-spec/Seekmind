package fr.corsicajack.seekmind.engine.ocr.recognition

import android.graphics.Bitmap
import android.os.SystemClock
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import fr.corsicajack.seekmind.core.common.IntRect
import fr.corsicajack.seekmind.core.model.OcrLine
import fr.corsicajack.seekmind.core.model.OcrOutcome
import fr.corsicajack.seekmind.core.model.config.OcrConfig
import fr.corsicajack.seekmind.core.model.port.OcrEngine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * [OcrEngine] backed by the bundled ML Kit model, Latin script (docs/specs/ocr.md §4): one recognizer,
 * whose `Task` is adapted into a suspending call, and the lines filtered by [OcrLineFilter]. One
 * instance per process; [close] when the accessibility service disconnects. Telemetry is cut by the
 * manifest (option B of prototype S2, docs/specs/ocr.md §4.1).
 *
 * A `Task` of ML Kit cannot be cancelled and reads the bitmap until it ends; the caller recycles the
 * bitmap as soon as [recognize] returns (INV-03). So [recognize] always waits for the end of its `Task`,
 * even when its coroutine is cancelled, and only then rethrows the cancellation; [close] releases the
 * recognizer once no recognition is running.
 */
class MlKitOcrEngine(
    config: OcrConfig = OcrConfig(),
    private val recognizer: TextRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS),
) : OcrEngine {
    private val filter = OcrLineFilter(config.minLineConfidence)

    // Guarded by lock.
    private val lock = Any()
    private var running = 0
    private var closeRequested = false
    private var closed = false

    /** Throws [IllegalStateException] once [close] was called. */
    override suspend fun recognize(bitmap: Bitmap): OcrOutcome {
        synchronized(lock) {
            check(!closeRequested) { "OCR engine closed" }
            running++
        }
        val start = SystemClock.elapsedRealtime()
        val text = try {
            withContext(NonCancellable) {
                suspendCoroutine { continuation ->
                    recognizer.process(InputImage.fromBitmap(bitmap, 0))
                        .addOnSuccessListener(DirectExecutor) { continuation.resume(it) }
                        .addOnFailureListener(DirectExecutor) { continuation.resumeWithException(it) }
                        .addOnCanceledListener(DirectExecutor) {
                            continuation.resumeWithException(CancellationException("ML Kit task cancelled"))
                        }
                }
            }
        } finally {
            synchronized(lock) {
                running--
                closeIfIdle()
            }
        }
        currentCoroutineContext().ensureActive()
        val lines = text.textBlocks.flatMap { block -> block.lines }.map { line ->
            val box = line.boundingBox
            OcrLine(
                text = line.text,
                bounds = if (box == null) EMPTY else IntRect(box.left, box.top, box.right, box.bottom),
                confidence = line.confidence.takeIf { it > 0f },
            )
        }.filter(filter::keep)
        return OcrOutcome(lines, SystemClock.elapsedRealtime() - start)
    }

    /**
     * Releases the native resources, at once or when the running recognitions end; the engine is
     * unusable afterwards.
     */
    fun close() = synchronized(lock) {
        closeRequested = true
        closeIfIdle()
    }

    private fun closeIfIdle() {
        if (closeRequested && running == 0 && !closed) {
            closed = true
            recognizer.close()
        }
    }

    private object DirectExecutor : java.util.concurrent.Executor {
        override fun execute(command: Runnable) = command.run()
    }

    private companion object {
        val EMPTY = IntRect(0, 0, 0, 0)
    }
}
