package fr.corsicajack.seekmind.engine.ocr.recognition

import fr.corsicajack.seekmind.core.model.OcrLine
import fr.corsicajack.seekmind.core.model.TextLine
import fr.corsicajack.seekmind.core.model.TextSource
import java.text.Normalizer
import java.util.Locale

/** The text of a screen once the lines of OCR are merged into those of the tree. */
data class MergedText(
    /** The lines of the tree, then the lines of OCR kept, from top to bottom. */
    val lines: List<String>,
    /** Bits of [TextSource]: OCR when at least one line of OCR was kept. */
    val textSource: Int,
    val ocrLinesKept: Int,
)

/**
 * Merges the lines of OCR with the text of the tree (docs/specs/ocr.md §7). A line of OCR is dropped
 * when its comparison form (lower case, no diacritics, single spaces) is contained in the text of the
 * tree, or when it shares at least [MIN_WORD_JACCARD] of its words (Jaccard) with a line of the tree.
 * The lines kept follow those of the tree, from top to bottom, then from left to right.
 */
object OcrMerge {
    const val MIN_WORD_JACCARD = 0.8

    fun merge(tree: List<TextLine>, ocr: List<OcrLine>): MergedText {
        val treeForms = tree.map { comparisonForm(it.text) }
        val treeText = treeForms.joinToString("\n")
        val treeWords = treeForms.map(::words)
        val kept = ocr
            .sortedWith(compareBy<OcrLine>({ it.bounds.top }, { it.bounds.left }))
            .filter { line ->
                val form = comparisonForm(line.text)
                if (form.isEmpty() || treeText.contains(form)) return@filter false
                val lineWords = words(form)
                treeWords.none { jaccard(lineWords, it) >= MIN_WORD_JACCARD }
            }
        var source = if (tree.isEmpty()) 0 else TextSource.TREE
        if (kept.isNotEmpty()) source = source or TextSource.OCR
        return MergedText(tree.map { it.text } + kept.map { it.text }, source, kept.size)
    }

    /** Lower case, without diacritics, whitespace reduced to single spaces. */
    fun comparisonForm(text: String): String {
        val decomposed = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        return DIACRITICS.replace(decomposed, "").trim().replace(WHITESPACE, " ")
    }

    private fun words(form: String): Set<String> = form.split(SEPARATOR).filterTo(HashSet()) { it.isNotEmpty() }

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val common = a.count { it in b }
        return common.toDouble() / (a.size + b.size - common)
    }

    private val DIACRITICS = Regex("\\p{Mn}+")
    private val WHITESPACE = Regex("\\s+")
    private val SEPARATOR = Regex("[^\\p{L}\\p{N}]+")
}
