package fr.corsicajack.seekmind.engine.embedding.tokenizer

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.assertThrows
import org.junit.Test

/** Reading of `vocab.bin` (docs/specs/embeddings.md §3.2). */
class VocabularyTest {
    private val pieces = listOf("${MARK}le" to -3.5f, "${MARK}chat" to -7.25f, "é" to -9f, "日本" to -12f)

    @Test
    fun `tokens and scores are read in id order`() {
        val vocabulary = Vocabulary.read(ByteArrayInputStream(vocabBlob(pieces)))
        assertThat(vocabulary.size).isEqualTo(8)
        for ((index, piece) in pieces.withIndex()) {
            val bytes = piece.first.toByteArray()
            assertThat(vocabulary.idOf(bytes, 0, bytes.size)).isEqualTo(index + 4)
            assertThat(vocabulary.score(index + 4)).isEqualTo(piece.second)
            assertThat(vocabulary.tokenBytes(index + 4)).isEqualTo(bytes)
        }
        assertThat(vocabulary.minScore).isEqualTo(-12f)
        assertThat(vocabulary.maxTokenBytes).isEqualTo("${MARK}chat".toByteArray().size)
    }

    @Test
    fun `a lookup compares the bytes, inside a larger buffer`() {
        val vocabulary = Vocabulary.parse(vocabBlob(pieces))
        val buffer = "xx${MARK}chatyy".toByteArray()
        val length = "${MARK}chat".toByteArray().size
        assertThat(vocabulary.idOf(buffer, 2, length)).isEqualTo(5)
        assertThat(vocabulary.idOf(buffer, 2, length - 1)).isEqualTo(-1)
        assertThat(vocabulary.idOf(buffer, 0, 2)).isEqualTo(-1)
    }

    @Test
    fun `special tokens are never looked up`() {
        val vocabulary = Vocabulary.parse(vocabBlob(pieces))
        for (special in listOf("<s>", "<pad>", "</s>", "<unk>")) {
            val bytes = special.toByteArray()
            assertThat(vocabulary.idOf(bytes, 0, bytes.size)).isEqualTo(-1)
        }
    }

    @Test
    fun `the first occurrence of a duplicated token wins`() {
        val vocabulary = Vocabulary.parse(vocabBlob(listOf("a" to -1f, "b" to -2f, "a" to -3f)))
        assertThat(vocabulary.idOf("a".toByteArray(), 0, 1)).isEqualTo(4)
    }

    @Test
    fun `many tokens are all found`() {
        val many = (0 until 5_000).map { "t$it" to -it.toFloat() }
        val vocabulary = Vocabulary.parse(vocabBlob(many))
        for ((index, piece) in many.withIndex()) {
            val bytes = piece.first.toByteArray()
            assertThat(vocabulary.idOf(bytes, 0, bytes.size)).isEqualTo(index + 4)
        }
        assertThat(vocabulary.idOf("t5000".toByteArray(), 0, 5)).isEqualTo(-1)
    }

    @Test
    fun `malformed files are refused`() {
        val good = vocabBlob(pieces)
        val badMagic = good.copyOf().also { it[0] = 'X'.code.toByte() }
        val truncated = good.copyOf(good.size - 1)
        val trailing = good + byteArrayOf(0)
        val noSpecials = vocabBlob(pieces, specials = false)
        val tooMany = good.copyOf().also { it[7] = 0x7F }
        for (blob in listOf(badMagic, truncated, trailing, noSpecials, tooMany, ByteArray(0))) {
            assertThrows(IOException::class.java) { Vocabulary.parse(blob) }
        }
    }

    @Test
    fun `the real vocabularies have the expected sizes`() {
        assertThat(ModelVocabularies.require("fr").size).isEqualTo(37_200)
        assertThat(ModelVocabularies.require("multi").size).isEqualTo(250_002)
    }

    private companion object {
        val MARK = Char(0x2581)
    }
}
