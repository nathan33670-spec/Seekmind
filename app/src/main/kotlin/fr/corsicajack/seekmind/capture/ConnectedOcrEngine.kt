package fr.corsicajack.seekmind.capture

import android.graphics.Bitmap
import fr.corsicajack.seekmind.core.model.OcrOutcome
import fr.corsicajack.seekmind.core.model.port.OcrEngine
import fr.corsicajack.seekmind.engine.capture.pipeline.ConnectedService

/**
 * One text recognizer for the process, closed when the service disconnects (docs/specs/ocr.md §4) and
 * created again at the next recognition. Closing waits for the running recognitions
 * ([fr.corsicajack.seekmind.engine.ocr.recognition.MlKitOcrEngine.close]).
 */
class ConnectedOcrEngine<E>(connected: ConnectedService, private val create: () -> E, private val close: (E) -> Unit) :
    OcrEngine where E : OcrEngine {
    private val lock = Any()
    private var engine: E? = null

    init {
        connected.onDisconnected {
            val current = synchronized(lock) { engine.also { engine = null } }
            current?.let(close)
        }
    }

    override suspend fun recognize(bitmap: Bitmap): OcrOutcome {
        val current = synchronized(lock) { engine ?: create().also { engine = it } }
        return current.recognize(bitmap)
    }
}
