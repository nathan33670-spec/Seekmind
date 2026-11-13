package fr.corsicajack.seekmind.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.font.FontWeight
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.designsystem.component.HighlightedText
import fr.corsicajack.seekmind.core.designsystem.theme.SeekMindTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HighlightedTextTest {
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun setUp() = compose.enableA11yChecksWhereSupported()

    @Test
    fun highlightsExactlyTheGivenRanges() {
        val text = "Rendez-vous au petit restaurant, celui des pâtes à la truffe."
        val truffe = text.indexOf("truffe")
        val restaurant = text.indexOf("restaurant")
        compose.setContent {
            SeekMindTheme(dynamicColor = false) {
                HighlightedText(
                    text = text,
                    highlights = listOf(truffe..truffe + 5, restaurant..restaurant + 9),
                )
            }
        }

        val shown = compose.onNodeWithText(text).fetchSemanticsNode().config[SemanticsProperties.Text].single()
        assertThat(shown.text).isEqualTo(text)
        val spans = shown.spanStyles.sortedBy { it.start }
        assertThat(
            spans.map {
                shown.text.substring(it.start, it.end)
            },
        ).containsExactly("restaurant", "truffe").inOrder()
        spans.forEach {
            assertThat(it.item.fontWeight).isEqualTo(FontWeight.Bold)
            assertThat(it.item.background).isNotEqualTo(Color.Unspecified)
        }
    }

    @Test
    fun withoutRangesTheTextIsPlain() {
        val text = "Aucun terme à surligner"
        compose.setContent {
            SeekMindTheme(dynamicColor = false) { HighlightedText(text = text, highlights = emptyList()) }
        }

        val shown = compose.onNodeWithText(text).fetchSemanticsNode().config[SemanticsProperties.Text].single()
        assertThat(shown.spanStyles).isEmpty()
    }
}
