package fr.corsicajack.seekmind.engine.search.query

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/** Same words as the FTS5 `unicode61 remove_diacritics 2` tokenizer (docs/specs/stockage.md §3.2). */
class TokenizerTest {
    private val cases = listOf(
        "Pâtes à la truffe" to listOf("pates", "a", "la", "truffe"),
        "ÉLÉPHANT Œuvre naïve" to listOf("elephant", "œuvre", "naive"),
        "e-mail: x_y@z.fr" to listOf("e", "mail", "x", "y", "z", "fr"),
        "l'été 2026" to listOf("l", "ete", "2026"),
        "ça coûte 12,50€" to listOf("ca", "coute", "12", "50"),
        "Ελληνικά Русский" to listOf("ελληνικά", "русский"),
        "日本語" to listOf("日本語"),
        "x²" to listOf("x²"),
        "" to emptyList(),
    )

    @Test
    fun `table of cases`() {
        for ((text, words) in cases) {
            assertWithMessage("<$text>").that(Tokenizer.tokenize(text)).isEqualTo(words)
        }
    }
}
