package fr.corsicajack.seekmind.core.testing

import fr.corsicajack.seekmind.core.model.port.EmbeddingEngine

/**
 * [EmbeddingEngine] without model (docs/06-qualite-tests-ci.md §2): deterministic unit vectors derived
 * from a hash of the prefixed text. Equal texts give equal vectors; different texts, unrelated ones.
 */
class FakeEmbeddingEngine(override val modelId: Int = MODEL_ID) : EmbeddingEngine {
    override val dimensions: Int = TestVectors.DIMENSIONS

    /** Texts received, prefixes included, in order: lets a test check what the engine was given. */
    val received = mutableListOf<String>()

    var closed = false
        private set

    override suspend fun embedPassages(texts: List<String>): List<FloatArray> = texts.map { vector("passage: $it") }

    override suspend fun embedQuery(text: String): FloatArray = vector("query: $text")

    override fun close() {
        closed = true
    }

    private fun vector(text: String): FloatArray {
        check(!closed) { "engine closed" }
        received += text
        return TestVectors.unit(seed = text.fold(1_125_899_906_842_597L) { hash, c -> 31 * hash + c.code })
    }

    companion object {
        /** Not a production model id (those are 1 and 2). */
        const val MODEL_ID = 999
    }
}
