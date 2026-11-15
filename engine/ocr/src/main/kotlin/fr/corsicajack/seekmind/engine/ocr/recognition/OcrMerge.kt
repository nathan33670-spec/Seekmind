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
 * when its words (comparison form: lower case, no diacritics) appear in a line of the tree, in the same
 * order and at word boundaries ("jeu" is not in "jeudi"), or when it shares at least [MIN_WORD_JACCARD]
 * of its words (Jaccard) with a line of the tree. The lines kept follow those of the tree in reading
 * order: by rows (lines whose heights overlap by more than half), each row from left to right.
 */
object OcrMerge {
    const val MIN_WORD_JACCARD = 0.8

    fun merge(tree: List<TextLine>, ocr: List<OcrLine>): MergedText {
        val treeWords = tree.map { wordList(comparisonForm(it.text)) }
        // One line of the tree per text line, each word between spaces: a match never spans two lines.
        val treeText = treeWords.joinToString("\n") { words -> words.joinToString(" ", " ", " ") }
        val treeSets = treeWords.map { it.toSet() }
        val kept = readingOrder(ocr).filter { line ->
            val words = wordList(comparisonForm(line.text))
            if (words.isEmpty() || treeText.contains(words.joinToString(" ", " ", " "))) return@filter false
            val set = words.toSet()
            treeSets.none { jaccard(set, it) >= MIN_WORD_JACCARD }
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

    /** Rows of lines whose vertical extents overlap by more than half the smaller height, from top to bottom; each row from left to right. */
    fun readingOrder(lines: List<OcrLine>): List<OcrLine> {
        val rows = mutableListOf<MutableList<OcrLine>>()
        for (line in lines.sortedBy { it.bounds.top }) {
            val row = rows.lastOrNull()
            if (row != null && sameRow(row.first(), line)) row += line else rows += mutableListOf(line)
        }
        return rows.flatMap { row -> row.sortedBy { it.bounds.left } }
    }

    private fun sameRow(a: OcrLine, b: OcrLine): Boolean {
        val overlap = minOf(a.bounds.bottom, b.bounds.bottom) - maxOf(a.bounds.top, b.bounds.top)
        val height = minOf(a.bounds.bottom - a.bounds.top, b.bounds.bottom - b.bounds.top)
        return height > 0 && overlap * 2 > height
    }

    private fun wordList(form: String): List<String> = form.split(SEPARATOR).filter { it.isNotEmpty() }

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val common = a.count { it in b }
        return common.toDouble() / (a.size + b.size - common)
    }

    private val DIACRITICS = Regex("\\p{Mn}+")
    private val WHITESPACE = Regex("\\s+")
    private val SEPARATOR = Regex("[^\\p{L}\\p{N}]+")
}
