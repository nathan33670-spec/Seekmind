package fr.corsicajack.seekmind.engine.search.query

import java.text.Normalizer

/**
 * Splits text into words the way the FTS5 `unicode61 remove_diacritics 2` tokenizer does
 * (docs/specs/stockage.md §3.2): Unicode letters, digits and private-use characters form words,
 * everything else separates them; words are lower-cased and Latin letters lose their diacritics
 * (SQLite only removes the diacritics of the Latin script).
 */
object Tokenizer {
    fun tokenize(text: String): List<String> {
        val folded = fold(text)
        val words = ArrayList<String>()
        val current = StringBuilder()
        var i = 0
        while (i < folded.length) {
            val codePoint = folded.codePointAt(i)
            if (isWordChar(codePoint)) {
                current.appendCodePoint(codePoint)
            } else if (current.isNotEmpty()) {
                words += current.toString()
                current.setLength(0)
            }
            i += Character.charCount(codePoint)
        }
        if (current.isNotEmpty()) words += current.toString()
        return words
    }

    /** Lower case, Latin letters without diacritics: the comparison form of a word. */
    fun fold(text: String): String {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
        val out = StringBuilder(decomposed.length)
        var latinBase = false
        var i = 0
        while (i < decomposed.length) {
            val codePoint = decomposed.codePointAt(i)
            val isMark = Character.getType(codePoint) == Character.NON_SPACING_MARK.toInt()
            if (!isMark) latinBase = Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.LATIN
            if (!isMark || !latinBase) out.appendCodePoint(Character.toLowerCase(codePoint))
            i += Character.charCount(codePoint)
        }
        return Normalizer.normalize(out, Normalizer.Form.NFC)
    }

    private fun isWordChar(codePoint: Int): Boolean = Character.isLetterOrDigit(codePoint) ||
        Character.getType(codePoint) == Character.PRIVATE_USE.toInt() ||
        Character.getType(codePoint) == Character.LETTER_NUMBER.toInt() ||
        Character.getType(codePoint) == Character.OTHER_NUMBER.toInt()
}
