package fr.corsicajack.seekmind.engine.index

import fr.corsicajack.seekmind.core.model.config.IndexConfig
import java.text.Normalizer

/**
 * Normalises the text of a capture before it is fingerprinted and stored (docs/specs/indexation.md §2).
 * Case and accents are kept: FTS5 folds them when indexing. Idempotent.
 */
class TextNormalizer(private val config: IndexConfig = IndexConfig()) {
    fun normalize(text: String): String {
        val cleaned = buildString(text.length) {
            var i = 0
            while (i < text.length) {
                val c = text[i]
                when {
                    // \r\n, a lone \r and the Unicode line and paragraph separators end a line.
                    c == '\r' -> {
                        append('\n')
                        if (i + 1 < text.length && text[i + 1] == '\n') i++
                    }

                    c == '\n' || c == LINE_SEPARATOR || c == PARAGRAPH_SEPARATOR -> append('\n')

                    c == '\t' -> append(' ')

                    // Controls (the highlight marks U+0002 and U+0003 among them) and invisible format
                    // characters: zero-width ones, direction marks and isolates, soft hyphen.
                    Character.getType(c).let {
                        it == Character.CONTROL.toInt() || it == Character.FORMAT.toInt()
                    } -> Unit

                    else -> append(c)
                }
                i++
            }
        }
        // NFC last: a removed character between a letter and its combining mark would otherwise keep
        // them apart until a second pass, and the normalisation would not be idempotent.
        val nfc = Normalizer.normalize(cleaned, Normalizer.Form.NFC)
        val seen = HashSet<String>()
        val lines = nfc.split('\n')
            .map { HORIZONTAL_SPACE.replace(it, " ").trim() }
            .filter { it.isNotEmpty() && seen.add(it) }
        return truncate(lines)
    }

    /** Whole lines up to [IndexConfig.maxTextChars]; a first line longer than that is cut. */
    private fun truncate(lines: List<String>): String {
        val out = StringBuilder()
        for (line in lines) {
            val needed = if (out.isEmpty()) line.length else out.length + 1 + line.length
            if (needed > config.maxTextChars) {
                if (out.isEmpty()) {
                    // Never keep half of a surrogate pair.
                    val end = config.maxTextChars.let { if (line[it - 1].isHighSurrogate()) it - 1 else it }
                    out.append(line, 0, end)
                }
                break
            }
            if (out.isNotEmpty()) out.append('\n')
            out.append(line)
        }
        return out.toString()
    }

    private companion object {
        val LINE_SEPARATOR = Char(0x2028)
        val PARAGRAPH_SEPARATOR = Char(0x2029)

        /** Spaces of a line: ASCII space and every Unicode space separator (no-break ones included). */
        val HORIZONTAL_SPACE = Regex("[\\p{Zs}]+")
    }
}
