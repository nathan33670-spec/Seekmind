package fr.corsicajack.seekmind.engine.ocr.recognition

import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.common.IntRect
import fr.corsicajack.seekmind.core.model.OcrLine
import fr.corsicajack.seekmind.core.model.TextSource
import fr.corsicajack.seekmind.core.testing.Models
import org.junit.Test

/** docs/specs/ocr.md §7. */
class OcrMergeTest {
    private fun ocr(text: String, top: Int = 0, left: Int = 0) =
        OcrLine(text, IntRect(left, top, left + 100, top + 20), 0.9f)

    private val tree =
        listOf(Models.textLine("Réunion d'équipe jeudi à 10 h", top = 0), Models.textLine("Salle Lyon", top = 40))

    @Test
    fun aLineAlreadyInTheTreeIsNotAdded() {
        val merged = OcrMerge.merge(tree, listOf(ocr("REUNION  d'equipe jeudi")))
        assertThat(merged.lines).containsExactly("Réunion d'équipe jeudi à 10 h", "Salle Lyon").inOrder()
        assertThat(merged.textSource).isEqualTo(TextSource.TREE)
        assertThat(merged.ocrLinesKept).isEqualTo(0)
    }

    @Test
    fun aLineCloseToATreeLineIsNotAdded() {
        // Six words shared with the first line out of seven in all: Jaccard 6/7 ≥ 0.8.
        val merged = OcrMerge.merge(tree, listOf(ocr("Reunion d equipe jeudi 10 h")))
        assertThat(merged.ocrLinesKept).isEqualTo(0)
    }

    @Test
    fun aNewLineIsAddedAfterTheTreeAndMarksTheOcr() {
        val merged = OcrMerge.merge(tree, listOf(ocr("Ordre du jour : budget 2027", top = 300)))
        assertThat(merged.lines.last()).isEqualTo("Ordre du jour : budget 2027")
        assertThat(merged.textSource).isEqualTo(TextSource.TREE or TextSource.OCR)
        assertThat(merged.ocrLinesKept).isEqualTo(1)
    }

    @Test
    fun theLinesKeptGoFromTopToBottomThenLeftToRight() {
        val merged = OcrMerge.merge(
            emptyList(),
            listOf(
                ocr("troisième ligne", top = 200),
                ocr("deuxième à droite", top = 100, left = 300),
                ocr("deuxième à gauche", top = 100),
                ocr("première ligne", top = 10),
            ),
        )
        assertThat(
            merged.lines,
        ).containsExactly("première ligne", "deuxième à gauche", "deuxième à droite", "troisième ligne").inOrder()
        assertThat(merged.textSource).isEqualTo(TextSource.OCR)
    }

    @Test
    fun withoutAnyLineOfOcrTheTextIsTheTree() {
        val merged = OcrMerge.merge(tree, emptyList())
        assertThat(merged.lines).hasSize(2)
        assertThat(merged.textSource).isEqualTo(TextSource.TREE)
    }

    @Test
    fun theComparisonFormDropsCaseDiacriticsAndSpaces() {
        assertThat(OcrMerge.comparisonForm("  Été   À\tNoël ")).isEqualTo("ete a noel")
    }
}
