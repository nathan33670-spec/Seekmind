package fr.corsicajack.seekmind.engine.embedding.tokenizer

import java.text.Normalizer

/**
 * Normalisation of the reference tokenizer (docs/specs/embeddings.md §3.1 step 1): an approximation of
 * the `nmt_nfkc` rule set, followed by the reduction of runs of spaces. Edge spaces are kept, as the
 * reference does.
 *
 * The differences between `nmt_nfkc` and NFKC were listed code point by code point against the
 * reference implementation: control characters removed, some separators and invisible characters
 * turned into a space, and a few characters added to Unicode after the rule set was compiled, which
 * it leaves as they are.
 */
internal object UnigramNormalizer {
    private const val SPACE = ' '

    fun normalize(text: String): String {
        if (text.isEmpty()) return text
        val out = StringBuilder(text.length)
        val segment = StringBuilder()
        var i = 0
        while (i < text.length) {
            val codePoint = text.codePointAt(i)
            i += Character.charCount(codePoint)
            when {
                isRemoved(codePoint) -> Unit

                isSpace(codePoint) -> segment.append(SPACE)

                isKept(codePoint) -> {
                    flush(segment, out)
                    out.appendCodePoint(codePoint)
                }

                else -> segment.appendCodePoint(codePoint)
            }
        }
        flush(segment, out)
        return collapseSpaces(out)
    }

    private fun flush(segment: StringBuilder, out: StringBuilder) {
        if (segment.isEmpty()) return
        out.append(Normalizer.normalize(segment, Normalizer.Form.NFKC))
        segment.setLength(0)
    }

    /** Runs of two spaces or more (U+0020 only) become one space. */
    private fun collapseSpaces(text: CharSequence): String {
        val out = StringBuilder(text.length)
        for (c in text) {
            if (c == SPACE && out.isNotEmpty() && out[out.length - 1] == SPACE) continue
            out.append(c)
        }
        return out.toString()
    }

    /** Control characters dropped by `nmt_nfkc`; U+0000 and most C1 controls are kept. */
    private fun isRemoved(c: Int): Boolean =
        c in 0x01..0x08 || c == 0x0B || c in 0x0E..0x1F || c == 0x7F || c == 0x8F || c == 0x9F

    /** Mapped to a space by `nmt_nfkc` while NFKC keeps them. */
    private fun isSpace(c: Int): Boolean = c == 0x09 || c == 0x0A || c == 0x0C || c == 0x0D ||
        c == 0x1680 || c in 0x200B..0x200F || c == 0x2028 || c == 0x2029 ||
        c == 0x2581 || c == 0xFEFF || c == 0xFFFD

    /** Decomposed by recent NFKC tables but absent from `nmt_nfkc`, which leaves them unchanged. */
    private fun isKept(c: Int): Boolean = c == 0xFF5E || c == 0x32FF || c in 0xA7F2..0xA7F4 || c == 0xAB69 ||
        (c in 0x10781..0x107BA && c != 0x10786 && c != 0x107B1) ||
        c in 0x1E030..0x1E06D || c == 0x1F16C || c == 0x1F23B || c in 0x1FBF0..0x1FBF9
}
