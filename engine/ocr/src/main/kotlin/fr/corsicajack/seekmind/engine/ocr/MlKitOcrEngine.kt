package fr.corsicajack.seekmind.engine.ocr

import android.graphics.Bitmap
import android.os.SystemClock
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import fr.corsicajack.seekmind.core.common.IntRect
import fr.corsicajack.seekmind.core.model.OcrLine
import fr.corsicajack.seekmind.core.model.OcrOutcome
import fr.corsicajack.seekmind.core.model.port.OcrEngine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * [OcrEngine] backed by the bundled ML Kit model, Latin script (docs/specs/ocr.md §4).
 * One instance per process; [close] when the accessibility service disconnects.
 */
class MlKitOcrEngine(
    private val recognizer: TextRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS),
) : OcrEngine {
    override suspend fun recognize(bitmap: Bitmap): OcrOutcome {
        val start = SystemClock.elapsedRealtime()
        val text = suspendCancellableCoroutine { continuation ->
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener(DirectExecutor) { continuation.resume(it) }
                .addOnFailureListener(DirectExecutor) { continuation.resumeWithException(it) }
        }
        val lines = text.textBlocks.flatMap { block -> block.lines }.map { line ->
            val box = line.boundingBox
            OcrLine(
                text = line.text,
                bounds = if (box == null) EMPTY else IntRect(box.left, box.top, box.right, box.bottom),
                confidence = line.confidence.takeIf { it > 0f },
            )
        }
        return OcrOutcome(lines, SystemClock.elapsedRealtime() - start)
    }

    /** Releases the native resources; the engine is unusable afterwards. */
    fun close() = recognizer.close()

    private object DirectExecutor : java.util.concurrent.Executor {
        override fun execute(command: Runnable) = command.run()
    }

    private companion object {
        val EMPTY = IntRect(0, 0, 0, 0)
    }
}
