package fr.corsicajack.seekmind.engine.embedding.tokenizer

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

/** Steps 2 to 6 of docs/specs/embeddings.md §3.1, on a small vocabulary, then on the `fr` vocabulary. */
class UnigramTokenizerTest {
    private val m = Char(0x2581).toString()

    // ids: 4 ▁, 5 ▁le, 6 ▁chat, 7 ▁ch, 8 at, 9 a, 10 t, 11 c, 12 h, 13 l, 14 e, 15 ▁c, 16 ab, 17 b
    private val small = UnigramTokenizer(
        vocabulary(
            m to -2f, "${m}le" to -3f, "${m}chat" to -5f, "${m}ch" to -3f, "at" to -3f,
            "a" to -4f, "t" to -4f, "c" to -4f, "h" to -4f, "l" to -4f, "e" to -4f, "${m}c" to -4f,
            "ab" to -6f, "b" to -3f,
        ),
    )

    private fun ids(text: String, max: Int = 512) = small.encode(text, max).inputIds.toList()

    @Test
    fun `the template surrounds the tokens and the mask is all ones`() {
        val encoding = small.encode("le chat", 512)
        assertThat(encoding.inputIds.toList()).isEqualTo(listOf(0, 5, 6, 2))
        assertThat(encoding.attentionMask.toList()).isEqualTo(listOf(1, 1, 1, 1))
        assertThat(encoding.size).isEqualTo(4)
    }

    @Test
    fun `viterbi keeps the segmentation with the highest total score`() {
        // ▁chat (-5) beats ▁ch + at (-6) and ▁c + h + at (-11).
        assertThat(ids("chat")).isEqualTo(listOf(0, 6, 2))
        // ▁ + a + b (-9) loses to ▁ + ab (-8)... and ▁ab does not exist.
        assertThat(ids("ab")).isEqualTo(listOf(0, 4, 16, 2))
    }

    @Test
    fun `an empty string gives the template only`() {
        assertThat(ids("")).isEqualTo(listOf(0, 2))
    }

    @Test
    fun `spaces only give one word mark`() {
        assertThat(ids(" ")).isEqualTo(listOf(0, 4, 2))
        assertThat(ids(" \t\n  ")).isEqualTo(listOf(0, 4, 2))
    }

    @Test
    fun `a trailing space gives a lone word mark`() {
        assertThat(ids("le ")).isEqualTo(listOf(0, 5, 4, 2))
    }

    @Test
    fun `consecutive unknown characters give one unknown token`() {
        assertThat(ids("lexyz")).isEqualTo(listOf(0, 5, 3, 2))
        assertThat(ids("le xyz")).isEqualTo(listOf(0, 5, 4, 3, 2))
        // Separated by a known character: two unknown tokens.
        assertThat(ids("lexaz")).isEqualTo(listOf(0, 5, 3, 9, 3, 2))
    }

    @Test
    fun `an emoji is an unknown token`() {
        assertThat(
            ids("le " + String(Character.toChars(0x1F600)) + String(Character.toChars(0x1F601))),
        ).isEqualTo(listOf(0, 5, 4, 3, 2))
    }

    @Test
    fun `special tokens spelled in the text are plain text`() {
        for (text in listOf("<s>", "le </s>", "<pad> <unk>")) {
            val inner = ids(text).drop(1).dropLast(1)
            assertThat(inner).containsNoneOf(0, 1, 2)
            assertThat(inner).isNotEmpty()
        }
    }

    @Test
    fun `a text longer than the limit is truncated and keeps the final end token`() {
        val text = List(100) { "le" }.joinToString(" ")
        assertThat(ids(text)).hasSize(102)
        val truncated = ids(text, max = 10)
        assertThat(truncated).isEqualTo(listOf(0) + List(8) { 5 } + listOf(2))
        assertThat(ids(text, max = 2)).isEqualTo(listOf(0, 2))
        assertThat(ids("chat chat", max = 3)).isEqualTo(listOf(0, 6, 2))
    }

    @Test
    fun `truncation keeps a prefix of the full encoding`() {
        val fr = UnigramTokenizer(ModelVocabularies.require("fr"))
        for (line in GoldenLines.read(ModelVocabularies.golden("fr")).take(200)) {
            val full = fr.encode(line.text, Int.MAX_VALUE).inputIds
            for (max in listOf(2, 3, 8, 16)) {
                val cut = fr.encode(line.text, max).inputIds
                val expected = if (full.size <= max) full else full.copyOf(max - 1) + Vocabulary.EOS_ID
                assertThat(cut).isEqualTo(expected)
            }
        }
    }

    @Test
    fun `fewer than two tokens is refused`() {
        assertThrows(IllegalArgumentException::class.java) { small.encode("le", 1) }
    }

    @Test
    fun `edge cases on the fr vocabulary match the reference implementation`() {
        val fr = UnigramTokenizer(ModelVocabularies.require("fr"))
        fun ids(text: String) = fr.encode(text, 512).inputIds.toList()
        assertThat(ids("")).isEqualTo(listOf(0, 2))
        assertThat(ids("   ")).isEqualTo(listOf(0, 6, 2))
        assertThat(ids(" a  b ")).isEqualTo(listOf(0, 10, 593, 6, 2))
        // Two emojis in a row: one unknown token.
        val emojis = String(Character.toChars(0x1F600)) + String(Character.toChars(0x1F601))
        assertThat(ids("$emojis a")).isEqualTo(listOf(0, 6, 3, 10, 2))
        // Joined by a zero-width joiner, which becomes a space.
        val family = String(Character.toChars(0x1F468)) + Char(0x200D) + String(Character.toChars(0x1F469)) + " x"
        assertThat(ids(family)).isEqualTo(listOf(0, 6, 3, 6, 3, 682, 2))
        assertThat(ids("ﬁ ½ ² Ｓ")).isEqualTo(listOf(0, 555, 20846, 102, 139, 2))
        // A passage of 256 tokens at most, </s> kept.
        val long = fr.encode(List(400) { "mot" }.joinToString(" "), 256).inputIds
        assertThat(long.size).isEqualTo(256)
        assertThat(long.last()).isEqualTo(Vocabulary.EOS_ID)
    }
}
