package fr.corsicajack.seekmind.engine.ocr.recognition

import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.common.IntRect
import fr.corsicajack.seekmind.core.model.OcrLine
import org.junit.Test

class OcrLineFilterTest {
    private val filter = OcrLineFilter(minConfidence = 0.45f)

    private fun line(text: String, confidence: Float? = null) = OcrLine(text, IntRect(0, 0, 10, 10), confidence)

    @Test
    fun threeLettersOrDigitsAreEnough() {
        assertThat(filter.keep(line("Ok!"))).isFalse()
        assertThat(filter.keep(line("abc"))).isTrue()
        assertThat(filter.keep(line("1 2 3"))).isTrue()
        assertThat(filter.keep(line("é-à-ù"))).isTrue()
        assertThat(filter.keep(line("— · —"))).isFalse()
    }

    @Test
    fun aLowConfidenceIsDroppedAndAMissingOneIsNot() {
        assertThat(filter.keep(line("Facture", confidence = 0.44f))).isFalse()
        assertThat(filter.keep(line("Facture", confidence = 0.45f))).isTrue()
        assertThat(filter.keep(line("Facture", confidence = null))).isTrue()
    }
}
