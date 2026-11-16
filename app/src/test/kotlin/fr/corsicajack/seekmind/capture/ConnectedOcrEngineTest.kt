package fr.corsicajack.seekmind.capture

import android.graphics.Bitmap
import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.model.OcrOutcome
import fr.corsicajack.seekmind.core.model.port.OcrEngine
import fr.corsicajack.seekmind.engine.capture.pipeline.ConnectedService
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** docs/specs/ocr.md §4: one recognizer for the process, closed when the service disconnects. */
class ConnectedOcrEngineTest {
    private class Engine : OcrEngine {
        var closed = false
        var calls = 0

        override suspend fun recognize(bitmap: Bitmap): OcrOutcome {
            check(!closed) { "closed" }
            calls++
            return OcrOutcome(emptyList(), durationMs = 0)
        }
    }

    private val connected = ConnectedService()
    private val created = mutableListOf<Engine>()
    private val ocr = ConnectedOcrEngine(connected, { Engine().also(created::add) }, { it.closed = true })

    @Test
    fun `one engine while connected, closed at the disconnection, a new one afterwards`() = runBlocking<Unit> {
        connected.service = SERVICE
        ocr.recognize(BITMAP)
        ocr.recognize(BITMAP)
        assertThat(created).hasSize(1)
        assertThat(created.single().calls).isEqualTo(2)

        connected.service = null
        assertThat(created.single().closed).isTrue()

        connected.service = SERVICE
        ocr.recognize(BITMAP)
        assertThat(created).hasSize(2)
        assertThat(created.last().closed).isFalse()
    }

    @Test
    fun `nothing is created by a disconnection`() {
        connected.service = SERVICE
        connected.service = null
        connected.service = null
        assertThat(created).isEmpty()
    }

    private companion object {
        /** Placeholders: on the JVM, Android classes are stubs that cannot be constructed. */
        private fun <T> allocate(type: Class<T>): T =
            Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").let { field ->
                field.isAccessible = true
                val unsafe = field.get(null)
                type.cast(unsafe.javaClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type))
            }

        val BITMAP: Bitmap = allocate(Bitmap::class.java)
        val SERVICE: android.accessibilityservice.AccessibilityService =
            allocate(fr.corsicajack.seekmind.engine.capture.SeekMindAccessibilityService::class.java)
    }
}
