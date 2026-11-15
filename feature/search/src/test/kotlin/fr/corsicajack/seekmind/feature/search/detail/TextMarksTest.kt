package fr.corsicajack.seekmind.feature.search.detail

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TextMarksTest {
    @Test
    fun termsAreFoundWithoutCaseOrAccents() {
        val text = "Élève du lycée : RÉSULTATS de l'élève"
        val ranges = TextMarks.highlights(text, listOf("eleve", "resultats"))
        assertThat(ranges.map { text.substring(it) }).containsExactly("Élève", "RÉSULTATS", "élève").inOrder()
    }

    @Test
    fun onlyAtTheStartOfAWord() {
        val text = "tarte et tartelette, une batarte"
        val ranges = TextMarks.highlights(text, listOf("tarte"))
        assertThat(ranges.map { it.first }).containsExactly(0, 9).inOrder()
        assertThat(text.substring(ranges[1])).isEqualTo("tarte") // the prefix of « tartelette » only
    }

    @Test
    fun codesAndEmptyTerms() {
        val text = "Code promo SUMMER20 valable"
        assertThat(
            TextMarks.highlights(text, listOf("summer20", "", " ")).map {
                text.substring(it)
            },
        ).containsExactly("SUMMER20")
        assertThat(TextMarks.highlights(text, emptyList())).isEmpty()
    }

    @Test
    fun foldingKeepsTheIndices() {
        val text = "Ça, c'est ÉTÉ"
        assertThat(TextMarks.fold(text)).hasLength(text.length)
        assertThat(TextMarks.fold(text)).isEqualTo("ca, c'est ete")
    }
}
