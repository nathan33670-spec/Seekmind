package fr.corsicajack.seekmind.feature.search.detail

import java.text.Normalizer

/** Where the search terms and the links are in a capture's text (docs/specs/ui-ux.md §3.3). Pure. */
object TextMarks {
    /**
     * Occurrences of [terms] that start a word, case and accents ignored, as FTS5 matches them
     * (`remove_diacritics`): « eleve » marks « Élève ». Each range covers the term, not the rest of the word.
     */
    fun highlights(text: String, terms: Collection<String>): List<IntRange> {
        val folded = fold(text)
        val wanted = terms.map(::fold).filter { it.isNotBlank() }.distinct()
        val ranges = ArrayList<IntRange>()
        for (term in wanted) {
            var from = folded.indexOf(term)
            while (from >= 0) {
                if (from == 0 || !folded[from - 1].isLetterOrDigit()) ranges += from until from + term.length
                from = folded.indexOf(term, from + 1)
            }
        }
        return ranges.sortedBy { it.first }
    }

    /** http(s) links of the text, in order, without the punctuation that ends a sentence. */
    fun links(text: String): List<String> = LINK.findAll(text).map { it.value.trimEnd(*TRAILING) }.distinct().toList()

    /** Lowercase without diacritics, one character for one: indices stay those of [text]. */
    internal fun fold(text: String): String = buildString(text.length) {
        for (c in text) {
            val base = Normalizer.normalize(c.toString(), Normalizer.Form.NFD)[0]
            append(base.lowercaseChar())
        }
    }

    private val LINK = Regex("""https?://[^\s<>"'«»]+""", RegexOption.IGNORE_CASE)
    private val TRAILING = charArrayOf('.', ',', ';', ':', '!', '?', ')', ']', '}', '\'', '"')
}
