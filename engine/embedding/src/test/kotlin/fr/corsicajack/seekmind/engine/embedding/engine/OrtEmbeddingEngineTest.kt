package fr.corsicajack.seekmind.engine.embedding.engine

import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.model.config.EmbeddingConfig
import fr.corsicajack.seekmind.engine.embedding.tokenizer.Encoding
import fr.corsicajack.seekmind.engine.embedding.tokenizer.Tokenizer
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Batching, padding, pooling and prefixes, with a tokenizer and an encoder that need no model. */
class OrtEmbeddingEngineTest {
    private val tokenizer = WordTokenizer()
    private val encoder = RecordingEncoder()

    private fun engine(config: EmbeddingConfig = EmbeddingConfig(), dispatcher: CoroutineDispatcher) =
        OrtEmbeddingEngine(BuiltInModels.FR, tokenizer, encoder, config, dispatcher)

    @Test
    fun theEngineAddsThePrefixesAndTheTokenLimits() = runTest {
        val engine = engine(dispatcher = StandardTestDispatcher(testScheduler))
        engine.embedQuery("horaires du musée")
        engine.embedPassages(listOf("ticket de caisse"))
        assertThat(
            tokenizer.calls,
        ).containsExactly("query: horaires du musée" to 64, "passage: ticket de caisse" to 256).inOrder()
    }

    @Test
    fun theSameTextGivesDifferentVectorsAsQueryAndAsPassage() = runTest {
        val engine = engine(dispatcher = StandardTestDispatcher(testScheduler))
        val query = engine.embedQuery("musée")
        val passage = engine.embedPassages(listOf("musée")).single()
        assertThat(query.toList()).isNotEqualTo(passage.toList())
    }

    @Test
    fun textsAreSortedByLengthInSubBatchesPaddedToTheirLongest() = runTest {
        val engine = engine(EmbeddingConfig(subBatchSize = 2), StandardTestDispatcher(testScheduler))
        val texts = listOf("a b c d e", "a", "a b c", "a b", "a b c d e f g")
        engine.embedPassages(texts)
        // "passage:" is one word: lengths with <s> and </s> are 8, 4, 6, 5, 10.
        assertThat(encoder.batches.map { it.rows to it.length }).containsExactly(2 to 5, 2 to 8, 1 to 10).inOrder()
        val first = encoder.batches[0]
        assertThat(first.attentionMask.toList()).containsExactly(1L, 1L, 1L, 1L, 0L, 1L, 1L, 1L, 1L, 1L).inOrder()
        assertThat(first.inputIds[4]).isEqualTo(PaddedBatch.PAD_ID)
    }

    @Test
    fun vectorsComeBackInTheOrderOfTheTexts() = runTest {
        val engine = engine(EmbeddingConfig(subBatchSize = 2), StandardTestDispatcher(testScheduler))
        val texts = listOf("a b c d e", "a", "a b c", "a b", "a b c d e f g", "b")
        val together = engine.embedPassages(texts)
        val alone = texts.map { engine.embedPassages(listOf(it)).single() }
        for (i in texts.indices) assertThat(together[i].toList()).isEqualTo(alone[i].toList())
    }

    @Test
    fun paddingDoesNotChangeTheVector() = runTest {
        val engine = engine(dispatcher = StandardTestDispatcher(testScheduler))
        val short = engine.embedPassages(listOf("a b")).single()
        val padded = engine.embedPassages(listOf("a b", "a b c d e f g h")).first()
        assertThat(padded.toList()).isEqualTo(short.toList())
    }

    @Test
    fun vectorsAreTheNormalisedMaskedMean() = runTest {
        val engine = engine(dispatcher = StandardTestDispatcher(testScheduler))
        val vector = engine.embedQuery("x")
        assertThat(sqrt(vector.sumOf { (it * it).toDouble() })).isWithin(1e-6).of(1.0)
        // The encoder gives token t of id i the state (i, t + 1, 1, 0, …): ids 0, 4, 5, 2 for "<s> query: x </s>".
        val mean = doubleArrayOf((0 + 4 + 5 + 2) / 4.0, 2.5, 1.0)
        val norm = sqrt(mean.sumOf { it * it })
        for (d in 0 until 3) assertThat(vector[d].toDouble()).isWithin(1e-6).of(mean[d] / norm)
        for (d in 3 until vector.size) assertThat(vector[d]).isEqualTo(0f)
    }

    @Test
    fun noTextNoComputation() = runTest {
        val engine = engine(dispatcher = StandardTestDispatcher(testScheduler))
        assertThat(engine.embedPassages(emptyList())).isEmpty()
        assertThat(encoder.batches).isEmpty()
    }

    @Test
    fun closingClosesTheEncoderOnce() = runTest {
        val engine = engine(dispatcher = StandardTestDispatcher(testScheduler))
        engine.close()
        engine.close()
        assertThat(encoder.closed).isEqualTo(1)
        assertThat(engine.isOpen).isFalse()
        assertThat(
            runCatching {
                engine.embedQuery("x")
            }.exceptionOrNull(),
        ).isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun modelIdAndDimensionsComeFromTheModel() = runTest {
        val engine = engine(dispatcher = StandardTestDispatcher(testScheduler))
        assertThat(engine.modelId).isEqualTo(1)
        assertThat(engine.dimensions).isEqualTo(RecordingEncoder.DIMENSIONS)
    }
}

/** One id per space-separated word, ids from 4; `<s>` and `</s>` around, truncated like the real one. */
private class WordTokenizer : Tokenizer {
    val calls = mutableListOf<Pair<String, Int>>()
    private val ids = mutableMapOf<String, Int>()

    override fun encode(text: String, maxTokens: Int): Encoding {
        calls += text to maxTokens
        val words = text.split(' ').filter { it.isNotEmpty() }.take(maxTokens - 2)
        val tokens = intArrayOf(0) + words.map { ids.getOrPut(it) { 4 + ids.size } }.toIntArray() + intArrayOf(2)
        return Encoding(tokens, IntArray(tokens.size) { 1 })
    }
}

/**
 * Records the batches; token t of id i gets the hidden state (i, t + 1, 1, 0, …) when unmasked, and
 * noise on the padding, which the pooling must ignore.
 */
private class RecordingEncoder : Encoder {
    val batches = mutableListOf<PaddedBatch>()
    var closed = 0
    override val dimensions = DIMENSIONS

    override fun encode(batch: PaddedBatch): FloatArray {
        batches += batch
        val out = FloatArray(batch.rows * batch.length * DIMENSIONS)
        for (row in 0 until batch.rows) {
            for (t in 0 until batch.length) {
                val offset = (row * batch.length + t) * DIMENSIONS
                if (batch.attentionMask[row * batch.length + t] == 0L) {
                    for (d in 0 until DIMENSIONS) out[offset + d] = 1_000f + d
                } else {
                    out[offset] = batch.inputIds[row * batch.length + t].toFloat()
                    out[offset + 1] = t + 1f
                    out[offset + 2] = 1f
                }
            }
        }
        return out
    }

    override fun close() {
        closed++
    }

    companion object {
        const val DIMENSIONS = 8
    }
}
