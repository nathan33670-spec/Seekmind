package fr.corsicajack.seekmind.engine.embedding.engine

import fr.corsicajack.seekmind.core.model.ModelSpec
import fr.corsicajack.seekmind.core.model.config.EmbeddingConfig
import fr.corsicajack.seekmind.core.model.port.EmbeddingEngine
import fr.corsicajack.seekmind.engine.embedding.tokenizer.Tokenizer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Sentence embeddings with the model of [spec] (docs/specs/embeddings.md §4): adds the prefix, tokenizes,
 * sorts the texts by length, runs sub-batches of [EmbeddingConfig.subBatchSize] padded to their longest
 * text, then takes the masked mean of the hidden states and normalises it. The vectors come back in the
 * order of the texts.
 *
 * Not thread-safe: the calls are serialised by [SharedEmbeddingEngineProvider]. Closing the engine
 * closes its [encoder].
 */
class OrtEmbeddingEngine(
    val spec: ModelSpec,
    private val tokenizer: Tokenizer,
    private val encoder: Encoder,
    private val config: EmbeddingConfig,
    private val dispatcher: CoroutineDispatcher,
) : EmbeddingEngine {
    override val modelId: Int get() = spec.modelId
    override val dimensions: Int get() = encoder.dimensions

    @Volatile
    var isOpen: Boolean = true
        private set

    override suspend fun embedPassages(texts: List<String>): List<FloatArray> =
        embed(texts.map { PASSAGE_PREFIX + it }, config.maxPassageTokens)

    override suspend fun embedQuery(text: String): FloatArray =
        embed(listOf(QUERY_PREFIX + text), config.maxQueryTokens).single()

    private suspend fun embed(texts: List<String>, maxTokens: Int): List<FloatArray> {
        check(isOpen) { "engine closed" }
        if (texts.isEmpty()) return emptyList()
        return withContext(dispatcher) {
            val encodings = texts.map { tokenizer.encode(it, maxTokens) }
            val vectors = arrayOfNulls<FloatArray>(texts.size)
            for (indices in Batching.plan(encodings, config.subBatchSize)) {
                ensureActive()
                val batch = PaddedBatch.of(indices.map { encodings[it] })
                val pooled = Pooling.maskedMeanNormalized(encoder.encode(batch), batch, encoder.dimensions)
                indices.forEachIndexed { row, index -> vectors[index] = pooled[row] }
            }
            vectors.map { checkNotNull(it) }
        }
    }

    override fun close() {
        if (!isOpen) return
        isOpen = false
        encoder.close()
    }

    companion object {
        const val QUERY_PREFIX = "query: "
        const val PASSAGE_PREFIX = "passage: "
    }
}
