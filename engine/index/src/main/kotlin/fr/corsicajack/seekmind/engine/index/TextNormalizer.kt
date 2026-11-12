package fr.corsicajack.seekmind.engine.index

import fr.corsicajack.seekmind.core.model.config.IndexConfig
import java.text.Normalizer

/**
 * Normalises the text of a capture before it is fingerprinted and stored (docs/specs/indexation.md §2).
 * Case and accents are kept: FTS5 folds them when indexing. Idempotent.
 */
class TextNormalizer(private val config: IndexConfig = IndexConfig()) {
    fun normalize(text: String): String {
        val nfc = Normalizer.normalize(text, Normalizer.Form.NFC)
        val cleaned = buildString(nfc.length) {
            for (c in nfc) {
                when {
                    c == '\n' -> append('\n')

                    c == '\t' -> append(' ')

                    // Controls (U+0002 and U+0003, the highlight marks, among them) and zero-width characters.
                    Character.getType(c) == Character.CONTROL.toInt() || c in ZERO_WIDTH -> Unit

                    else -> append(c)
                }
            }
        }
        val seen = HashSet<String>()
        val lines = cleaned.split('\n')
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
                if (out.isEmpty()) out.append(line, 0, config.maxTextChars)
                break
            }
            if (out.isNotEmpty()) out.append('\n')
            out.append(line)
        }
        return out.toString()
    }

    private companion object {
        /** Zero width space, non-joiner, joiner, word joiner, byte order mark. */
        val ZERO_WIDTH = setOf(0x200B, 0x200C, 0x200D, 0x2060, 0xFEFF).map(::Char).toSet()

        /** Spaces of a line: ASCII space and every Unicode space separator (no-break ones included). */
        val HORIZONTAL_SPACE = Regex("[\\p{Zs}]+")
    }
}
