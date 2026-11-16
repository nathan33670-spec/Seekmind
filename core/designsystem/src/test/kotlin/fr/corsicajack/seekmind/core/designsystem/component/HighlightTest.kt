package fr.corsicajack.seekmind.core.designsystem.component

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HighlightTest {
    private val style = SpanStyle(fontWeight = FontWeight.Bold)

    @Test
    fun `each range becomes one span covering exactly its characters`() {
        val text = "pâtes à la truffe, près de la gare"
        val annotated = highlight(text, listOf(0..4, 11..16), style)

        assertThat(annotated.text).isEqualTo(text)
        val spans = annotated.spanStyles.map { it.start until it.end }
        assertThat(spans).containsExactly(0 until 5, 11 until 17).inOrder()
        assertThat(annotated.spanStyles.map { it.item }.distinct()).containsExactly(style)
        assertThat(text.substring(11, 17)).isEqualTo("truffe")
    }

    @Test
    fun `overlapping and adjacent ranges are merged`() {
        assertThat(mergedRanges(20, listOf(5..8, 2..6, 9..10, 14..15))).containsExactly(2..10, 14..15).inOrder()
    }

    @Test
    fun `out of bounds, reversed and empty ranges are clipped or ignored`() {
        assertThat(mergedRanges(10, listOf(-3..2, 8..40, 6..4, IntRange.EMPTY))).containsExactly(0..2, 8..9).inOrder()
        assertThat(mergedRanges(0, listOf(0..3))).isEmpty()
    }

    @Test
    fun `no range leaves the text plain`() {
        assertThat(highlight("abc", emptyList(), style).spanStyles).isEmpty()
    }
}
