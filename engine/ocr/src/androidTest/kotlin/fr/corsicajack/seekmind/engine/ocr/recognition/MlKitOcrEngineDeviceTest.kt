package fr.corsicajack.seekmind.engine.ocr.recognition

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [MlKitOcrEngine] on three reference images drawn at run time (docs/specs/ocr.md §9, ticket M3-03):
 * light text on a dark background, dark on light, and a small type size.
 */
@RunWith(AndroidJUnit4::class)
class MlKitOcrEngineDeviceTest {
    private val engine = MlKitOcrEngine()

    @After
    fun tearDown() = engine.close()

    @Test
    fun lightTextOnADarkBackground() =
        assertRecognized(background = Color.rgb(24, 24, 28), ink = Color.WHITE, size = 56f)

    @Test
    fun darkTextOnALightBackground() =
        assertRecognized(background = Color.WHITE, ink = Color.rgb(30, 30, 30), size = 56f)

    @Test
    fun aSmallTypeSize() = assertRecognized(background = Color.WHITE, ink = Color.BLACK, size = 26f)

    @Test
    fun linesBelowThreeCharactersAreFilteredOut() = runBlocking {
        val bitmap = draw(Color.WHITE, Color.BLACK, 64f, listOf("OK", LINES.first()))
        try {
            val texts = engine.recognize(bitmap).lines.map { it.text }
            assertThat(texts.none { it.trim() == "OK" }).isTrue()
            assertThat(texts.joinToString(" ").lowercase()).contains("facture")
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * A cancelled recognition returns only once ML Kit is done with the bitmap: recycling it then, as
     * the caller does, is safe, and the engine still works.
     */
    @Test
    fun aCancelledRecognitionWaitsForMlKitBeforeReturning() = runBlocking {
        repeat(5) {
            val bitmap = draw(Color.WHITE, Color.BLACK, 56f, LINES)
            val recognition =
                async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { engine.recognize(bitmap) }
            recognition.cancel()
            val outcome = runCatching { recognition.await() }
            assertThat(outcome.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
            bitmap.recycle()
        }
        assertRecognized(background = Color.WHITE, ink = Color.BLACK, size = 56f)
    }

    /** close() during a recognition lets it finish; later calls are refused. */
    @Test
    fun closeWaitsForTheRunningRecognition() = runBlocking {
        val closing = MlKitOcrEngine()
        val bitmap = draw(Color.WHITE, Color.BLACK, 56f, LINES)
        try {
            val recognition =
                async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { closing.recognize(bitmap) }
            closing.close()
            val text = OcrMerge.comparisonForm(recognition.await().lines.joinToString(" ") { it.text })
            assertThat(text).contains(OcrMerge.comparisonForm(LINES.first()))
            assertThrows(IllegalStateException::class.java) { runBlocking { closing.recognize(bitmap) } }
        } finally {
            closing.close()
            bitmap.recycle()
        }
    }

    private fun assertRecognized(background: Int, ink: Int, size: Float) = runBlocking {
        val bitmap = draw(background, ink, size, LINES)
        try {
            val outcome = engine.recognize(bitmap)
            val recognized = OcrMerge.comparisonForm(outcome.lines.joinToString(" ") { it.text })
            for (expected in LINES) assertThat(recognized).contains(OcrMerge.comparisonForm(expected))
            assertThat(outcome.lines.all { it.bounds.bottom > it.bounds.top }).isTrue()
        } finally {
            bitmap.recycle()
        }
    }

    private fun draw(background: Int, ink: Int, size: Float, lines: List<String>): Bitmap {
        val bitmap = Bitmap.createBitmap(1080, 600, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            textSize = size
        }
        Canvas(bitmap).apply {
            drawColor(background)
            lines.forEachIndexed { i, line -> drawText(line, 40f, 120f + i * size * 2.2f, paint) }
        }
        return bitmap
    }

    private companion object {
        // Synthetic text only: no personal data (AGENTS.md §6).
        val LINES = listOf("Facture numero 2026", "Livraison prevue mardi", "Total 42 euros")
    }
}
