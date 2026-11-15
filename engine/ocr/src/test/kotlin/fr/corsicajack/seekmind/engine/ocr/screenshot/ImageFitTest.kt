package fr.corsicajack.seekmind.engine.ocr.screenshot

import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.common.IntRect
import org.junit.Test

class ImageFitTest {
    @Test
    fun `a small image without crop is used as it is`() {
        val fit = ImageFit.plan(1_000, 1_500, cropTo = null, maxSide = 1_600)!!
        assertThat(fit).isEqualTo(ImageFit(IntRect(0, 0, 1_000, 1_500), 1_000, 1_500))
        assertThat(fit.isIdentity(1_000, 1_500)).isTrue()
    }

    @Test
    fun `the longest side is reduced to the maximum, keeping the ratio`() {
        val fit = ImageFit.plan(1_080, 2_400, cropTo = null, maxSide = 1_600)!!
        assertThat(fit.height).isEqualTo(1_600)
        assertThat(fit.width).isEqualTo(720)
        assertThat(fit.isIdentity(1_080, 2_400)).isFalse()
    }

    @Test
    fun `a landscape image is reduced on its width`() {
        val fit = ImageFit.plan(3_200, 1_000, cropTo = null, maxSide = 1_600)!!
        assertThat(fit.width).isEqualTo(1_600)
        assertThat(fit.height).isEqualTo(500)
    }

    @Test
    fun `an image exactly at the maximum is kept`() {
        val fit = ImageFit.plan(1_600, 900, cropTo = null, maxSide = 1_600)!!
        assertThat(fit.isIdentity(1_600, 900)).isTrue()
    }

    @Test
    fun `the crop is the window, clamped to the image`() {
        val fit = ImageFit.plan(1_080, 2_400, cropTo = IntRect(-10, 100, 1_200, 1_100), maxSide = 1_600)!!
        assertThat(fit.crop).isEqualTo(IntRect(0, 100, 1_080, 1_100))
        assertThat(fit.width).isEqualTo(1_080)
        assertThat(fit.height).isEqualTo(1_000)
        assertThat(fit.isIdentity(1_080, 2_400)).isFalse()
    }

    @Test
    fun `a crop then a reduction`() {
        val fit = ImageFit.plan(1_440, 3_200, cropTo = IntRect(0, 0, 1_440, 3_000), maxSide = 1_600)!!
        assertThat(fit.crop).isEqualTo(IntRect(0, 0, 1_440, 3_000))
        assertThat(fit.height).isEqualTo(1_600)
        assertThat(fit.width).isEqualTo(768)
    }

    @Test
    fun `a window covering the whole image is no crop`() {
        val fit = ImageFit.plan(1_000, 1_000, cropTo = IntRect(0, 0, 1_000, 1_000), maxSide = 1_600)!!
        assertThat(fit.isIdentity(1_000, 1_000)).isTrue()
    }

    @Test
    fun `a window outside the image leaves nothing`() {
        assertThat(ImageFit.plan(1_000, 1_000, cropTo = IntRect(1_000, 0, 1_200, 500), maxSide = 1_600)).isNull()
        assertThat(ImageFit.plan(1_000, 1_000, cropTo = IntRect(0, 0, 0, 0), maxSide = 1_600)).isNull()
    }

    @Test
    fun `an empty image leaves nothing`() {
        assertThat(ImageFit.plan(0, 1_000, cropTo = null, maxSide = 1_600)).isNull()
    }

    @Test
    fun `a very thin image keeps at least one pixel`() {
        val fit = ImageFit.plan(10_000, 2, cropTo = null, maxSide = 1_600)!!
        assertThat(fit.width).isEqualTo(1_600)
        assertThat(fit.height).isEqualTo(1)
    }
}
