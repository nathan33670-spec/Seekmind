package fr.corsicajack.seekmind.engine.embedding.tokenizer

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Step 1 of docs/specs/embeddings.md §3.1; expected values from the reference implementation. */
class UnigramNormalizerTest {
    @Test
    fun `table of cases`() {
        val cases = listOf(
            "" to "",
            "café" to "café",
            // Decomposed accent, composed by NFKC.
            "cafe" + Char(0x0301) to "café",
            // Compatibility characters.
            "ﬁchier ½ m² Ｓｅｅｋ" to "fichier 1" + Char(0x2044) + "2 m2 Seek",
            // Tab, line feed, carriage return, form feed: a space.
            "a\tb\nc\rd" + Char(0x0C) + "e" to "a b c d e",
            // Non-breaking, thin, narrow and ideographic spaces: a space (NFKC).
            "a" + Char(0xA0) + "b" + Char(0x2009) + "c" + Char(0x202F) + "d" + Char(0x3000) + "e" to "a b c d e",
            // Zero-width characters, byte order mark, line and paragraph separators: a space.
            "a" + Char(0x200B) + "b" + Char(0x200D) + "c" + Char(0xFEFF) + "d" + Char(0x2028) + "e" to "a b c d e",
            // The word mark itself and the replacement character: a space.
            "a" + Char(0x2581) + "b" + Char(0xFFFD) + "c" to "a b c",
            // Control characters removed; NUL, U+0085 and the soft hyphen kept.
            "a" + Char(0x01) + "b" + Char(0x7F) + "c" + Char(0x0B) + "d" + Char(0x9F) + "e" to "abcde",
            "a" + Char(0) + "b" + Char(0x85) + "c" + Char(0xAD) + "d" to
                "a" + Char(0) + "b" + Char(0x85) + "c" + Char(0xAD) + "d",
            // Runs of spaces reduced; edge spaces kept, as the reference does.
            "  a   b  " to " a b ",
            "   " to " ",
            // Absent from the rule set: unchanged, although NFKC decomposes it.
            "a" + Char(0xFF5E) + "b" to "a" + Char(0xFF5E) + "b",
        )
        for ((input, expected) in cases) {
            assertThat(UnigramNormalizer.normalize(input)).isEqualTo(expected)
        }
    }

    @Test
    fun `normalisation is idempotent on the reference sentences`() {
        val sentences = GoldenLines.read(ModelVocabularies.golden("fr")).map { it.text }
        for (sentence in sentences) {
            val once = UnigramNormalizer.normalize(sentence)
            assertThat(UnigramNormalizer.normalize(once)).isEqualTo(once)
        }
    }
}
