package fr.corsicajack.seekmind.engine.index

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import fr.corsicajack.seekmind.core.model.config.IndexConfig
import org.junit.Test

/** docs/specs/indexation.md §2 (ticket M4-01). */
class TextNormalizerTest {
    private val normalizer = TextNormalizer()

    private val cases = listOf(
        // NFC: a decomposed "é" becomes one character.
        "cafe$ACUTE" to "caf$E_ACUTE",
        // Controls removed, except new lines; the highlight marks U+0002 and U+0003 among them.
        "a${Char(0x00)}b${Char(0x07)}c" to "abc",
        "${STX}pâtes$ETX à la truffe" to "pâtes à la truffe",
        "ligne 1\r\nligne 2" to "ligne 1\nligne 2",
        // Zero-width characters removed.
        "mot${ZWSP}coupé$ZWNJ$ZWJ$WORD_JOINER$BOM" to "motcoupé",
        // Tabs and runs of spaces, no-break ones included, become one space; edges trimmed.
        "  un\t\tdeux   trois  " to "un deux trois",
        "prix$NBSP:${NARROW_NBSP}12$NBSP€" to "prix : 12 €",
        // Empty lines removed; identical lines kept once, the first one.
        "a\n\n   \nb\na\nc\nb" to "a\nb\nc",
        // Case and accents kept.
        "Crème Brûlée" to "Crème Brûlée",
        "" to "",
    )

    @Test
    fun `table of cases`() {
        for ((input, expected) in cases) {
            assertWithMessage("normalize(%s)", input).that(normalizer.normalize(input)).isEqualTo(expected)
        }
    }

    @Test
    fun `normalisation is idempotent`() {
        val samples = cases.map { it.first } + listOf(
            "\t${STX}Mixte$ETX$ZWSP\n\n  e${ACUTE}té  $NBSP\nMixte\nfin\r",
            (1..300).joinToString("\n") { "ligne numéro $it avec du texte" },
        )
        for (text in samples) {
            val once = normalizer.normalize(text)
            assertWithMessage("idempotent on %s", text).that(normalizer.normalize(once)).isEqualTo(once)
        }
    }

    @Test
    fun `the text is cut on a line end`() {
        val small = TextNormalizer(IndexConfig(maxTextChars = 20))
        assertThat(small.normalize("douze chars\nencore huit\nfin")).isEqualTo("douze chars")
        assertThat(small.normalize("dix chars!\nneuf char")).isEqualTo("dix chars!\nneuf char")
        // A first line longer than the limit is cut at the limit.
        assertThat(small.normalize("x".repeat(30) + "\ny")).isEqualTo("x".repeat(20))
    }

    @Test
    fun `the default limit is 12 000 characters`() {
        val text = (1..2_000).joinToString("\n") { "ligne $it du texte" }
        val result = normalizer.normalize(text)
        assertThat(result.length).isAtMost(12_000)
        assertThat(result).endsWith("du texte")
    }

    private companion object {
        val ACUTE = Char(0x0301)
        val E_ACUTE = Char(0x00E9)
        val STX = Char(0x0002)
        val ETX = Char(0x0003)
        val ZWSP = Char(0x200B)
        val ZWNJ = Char(0x200C)
        val ZWJ = Char(0x200D)
        val WORD_JOINER = Char(0x2060)
        val BOM = Char(0xFEFF)
        val NBSP = Char(0x00A0)
        val NARROW_NBSP = Char(0x202F)
    }
}
