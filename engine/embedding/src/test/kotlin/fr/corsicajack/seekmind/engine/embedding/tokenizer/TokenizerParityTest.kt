package fr.corsicajack.seekmind.engine.embedding.tokenizer

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * Parity with the reference tokenizer on the 1,000 sentences of each model (docs/specs/embeddings.md
 * §3.3, ticket M4-03). Divergent sentences are listed in `build/reports/tokenizer/parity-<id>.txt`.
 */
class TokenizerParityTest {
    @Test
    fun `fr reproduces at least 99,5 percent of the reference sentences`() = checkParity("fr")

    @Test
    fun `multi reproduces at least 99,5 percent of the reference sentences`() = checkParity("multi")

    private fun checkParity(id: String) {
        val tokenizer = UnigramTokenizer(ModelVocabularies.require(id))
        val lines = GoldenLines.read(ModelVocabularies.golden(id))
        assertThat(lines).hasSize(SENTENCES)
        val divergent = lines.filterNot { tokenizer.encode(it.text, Int.MAX_VALUE).inputIds.contentEquals(it.ids) }
        val report = buildString {
            appendLine("$id: ${lines.size - divergent.size}/${lines.size} identical, ${divergent.size} divergent")
            for (line in divergent) {
                appendLine("text:     ${escape(line.text)}")
                appendLine("expected: ${line.ids.joinToString(" ")}")
                appendLine("actual:   ${tokenizer.encode(line.text, Int.MAX_VALUE).inputIds.joinToString(" ")}")
            }
        }
        File("build/reports/tokenizer").apply { mkdirs() }.resolve("parity-$id.txt").writeText(report)
        println(report)
        assertThat(lines.size - divergent.size).isAtLeast(MIN_IDENTICAL)
    }

    /** Control and invisible characters are written as code points, so that the report stays readable. */
    private fun escape(text: String): String = buildString {
        text.codePoints().forEach { c ->
            if (Character.isISOControl(c) || Character.getType(c) == Character.FORMAT.toInt()) {
                append("<U+%04X>".format(c))
            } else {
                appendCodePoint(c)
            }
        }
    }

    private companion object {
        const val SENTENCES = 1_000
        const val MIN_IDENTICAL = 995
    }
}
