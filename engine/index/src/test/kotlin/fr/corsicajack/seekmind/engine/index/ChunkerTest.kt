package fr.corsicajack.seekmind.engine.index

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import fr.corsicajack.seekmind.core.model.config.IndexConfig
import kotlin.random.Random
import org.junit.Test

/** docs/specs/indexation.md §5 (ticket M4-02). */
class ChunkerTest {
    private val chunker = Chunker()

    @Test
    fun `short text is one fragment covering it all`() {
        val text = "une ligne\nune autre ligne"
        assertThat(chunker.split(text)).containsExactly(ChunkSpan(0, text.length))
    }

    @Test
    fun `fragments are whole lines of at most 800 characters`() {
        val lines = List(30) { "ligne $it " + "x".repeat(60) }
        val text = lines.joinToString("\n")
        val spans = chunker.split(text)
        assertThat(spans.size).isAtMost(4)
        for (span in spans) {
            assertThat(span.length).isAtMost(800)
            // Starts at a line start, ends after a line break or at the end of the text.
            assertThat(span.start == 0 || text[span.start - 1] == '\n').isTrue()
            assertThat(span.end == text.length || text[span.end - 1] == '\n').isTrue()
        }
    }

    @Test
    fun `a long line is cut after a sentence end, else after a space`() {
        val sentence = "Une phrase courte. "
        val line = sentence.repeat(60) // 1 140 characters
        val spans = chunker.split(line)
        assertThat(spans[0].length).isAtMost(800)
        assertThat(line.substring(spans[0].start, spans[0].end)).endsWith(". ")

        val words = "mot ".repeat(300) // no sentence end
        val cut = chunker.split(words)[0]
        assertThat(cut.length).isAtMost(800)
        assertThat(words.substring(cut.start, cut.end)).endsWith(" ")

        val solid = "x".repeat(2_000) // nowhere to cut
        assertThat(chunker.split(solid)[0]).isEqualTo(ChunkSpan(0, 800))
    }

    @Test
    fun `at most four fragments, the rest is left out`() {
        val text = List(200) { "ligne numéro $it avec un peu de texte" }.joinToString("\n")
        val spans = chunker.split(text)
        assertThat(spans).hasSize(4)
        assertThat(spans.last().end).isLessThan(text.length)
    }

    @Test
    fun `the slices put end to end give back the covered text`() {
        val random = Random(42)
        repeat(500) {
            val text = List(random.nextInt(1, 40)) {
                val words = List(random.nextInt(0, 120)) { "m".repeat(random.nextInt(1, 12)) }
                words.joinToString(if (random.nextInt(5) == 0) ". " else " ")
            }.joinToString("\n")
            val spans = Chunker(IndexConfig()).split(text)
            if (text.isEmpty()) return@repeat
            assertWithMessage("first fragment starts the text").that(spans.first().start).isEqualTo(0)
            spans.zipWithNext().forEach { (a, b) -> assertThat(b.start).isEqualTo(a.end) }
            val covered = spans.joinToString("") { text.substring(it.start, it.end) }
            assertThat(covered).isEqualTo(text.substring(0, spans.last().end))
            spans.forEach { assertThat(it.length).isIn(1..800) }
        }
    }

    @Test
    fun `empty text has no fragment`() {
        assertThat(chunker.split("")).isEmpty()
    }
}
