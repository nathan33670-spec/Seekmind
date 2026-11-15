package fr.corsicajack.seekmind.engine.ocr.probe

import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.model.config.OcrConfig
import org.junit.Test

/** The heuristic on synthetic images (docs/specs/ocr.md §3.2). */
class BlackFrameTest {
    private val config = OcrConfig()

    private fun isBlack(width: Int = 1_080, height: Int = 2_400, pixel: (Int, Int) -> Int) =
        BlackFrame.isBlack(width, height, config, pixel)

    private fun grey(level: Int) = (0xFF shl 24) or (level shl 16) or (level shl 8) or level

    @Test
    fun `a black image is black`() {
        assertThat(isBlack { _, _ -> grey(0) }).isTrue()
    }

    @Test
    fun `an almost black image is black`() {
        assertThat(isBlack { _, _ -> grey(config.blackLumaMax) }).isTrue()
        assertThat(isBlack { _, _ -> grey(config.blackLumaMax + 1) }).isFalse()
    }

    @Test
    fun `a dark screen with text is not black`() {
        // Dark theme: a near-black background, with light words on lines spread over the height.
        val text = { x: Int, y: Int -> (x / 30) % 2 == 0 && y % 100 in 40..60 }
        val image = { x: Int, y: Int -> if (text(x, y)) grey(220) else grey(18) }
        assertThat(isBlack(pixel = image)).isFalse()
        // The same text on a background dark enough to count: the text alone keeps it from being black.
        val blacker = { x: Int, y: Int -> if (text(x, y)) grey(220) else grey(2) }
        assertThat(isBlack(pixel = blacker)).isFalse()
    }

    @Test
    fun `a light screen is not black`() {
        assertThat(isBlack { _, _ -> grey(250) }).isFalse()
    }

    @Test
    fun `a few bright samples do not prevent a black verdict`() {
        // A blacked-out window with a status bar or a cursor: under 2 % of the samples.
        val image = { x: Int, y: Int ->
            if (y < 100 && x < 1_080 / config.blackSampleGrid) grey(255) else grey(0)
        }
        assertThat(isBlack(pixel = image)).isTrue()
    }

    @Test
    fun `an empty image is not black`() {
        assertThat(isBlack(width = 0, height = 0) { _, _ -> grey(0) }).isFalse()
    }

    @Test
    fun `luma weighs green most`() {
        assertThat(BlackFrame.luma(0xFF00FF00.toInt())).isGreaterThan(BlackFrame.luma(0xFFFF0000.toInt()))
        assertThat(BlackFrame.luma(0xFFFFFFFF.toInt())).isEqualTo(255)
        assertThat(BlackFrame.luma(0xFF000000.toInt())).isEqualTo(0)
    }
}
