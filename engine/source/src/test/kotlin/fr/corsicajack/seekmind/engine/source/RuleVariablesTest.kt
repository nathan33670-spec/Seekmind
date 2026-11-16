package fr.corsicajack.seekmind.engine.source

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The variables of the rules (docs/specs/retour-source.md §4, ticket M7-02). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Config.TARGET_SDK])
class RuleVariablesTest {
    private fun raw(name: String, text: String, title: String? = null) =
        RuleVariables.raw(name, capture(NOTES, text = text, title = title))

    @Test
    fun theTitleIsTheWindowTitleOrElseTheFirstLine() {
        assertThat(raw("title", "\n  Tarte aux pommes \nRecette", title = "Recettes")).isEqualTo("Recettes")
        assertThat(raw("title", "\n  Tarte aux pommes \nRecette", title = "  ")).isEqualTo("Tarte aux pommes")
        assertThat(raw("firstLine", "\n  Tarte aux pommes \nRecette", title = "Recettes")).isEqualTo("Tarte aux pommes")
        assertThat(raw("title", "   \n ")).isNull()
    }

    @Test
    fun theHandleIsTheFirstAtNameButNotAnEmailAddress() {
        assertThat(raw("handle", "Écrire à marie@example.fr ou suivre @marie.dupont_ !")).isEqualTo("marie.dupont_")
        assertThat(raw("handle", "Profil de @jo. Fin")).isEqualTo("jo")
        assertThat(raw("handle", "@a seul, puis @@double")).isNull()
        assertThat(raw("handle", "contact: a@b.c")).isNull()
    }

    @Test
    fun valuesAreEncodedAndBounded() {
        val capture = capture(NOTES, text = "x", title = "Tarte & crème/brûlée ?#")
        assertThat(
            RuleVariables.encoded("title", capture),
        ).isEqualTo("Tarte%20%26%20cr%C3%A8me%2Fbr%C3%BBl%C3%A9e%20%3F%23")
        val long = "a".repeat(150)
        assertThat(RuleVariables.encoded("title", capture(NOTES, text = long))).isEqualTo("a".repeat(100))
        // A surrogate pair is never cut in half.
        val emoji = "b".repeat(99) + "😀" + "c"
        assertThat(RuleVariables.bounded(emoji)).isEqualTo("b".repeat(99))
        assertThat(RuleVariables.encoded("unknown", capture)).isNull()
    }
}
