package fr.corsicajack.seekmind.engine.embedding.tokenizer

/** Turns a text into the inputs of the encoder (docs/specs/embeddings.md §3). */
interface Tokenizer {
    /**
     * `<s>` + tokens + `</s>`, truncated to at most [maxTokens] ids with the final `</s>` kept.
     * [maxTokens] is at least 2.
     */
    fun encode(text: String, maxTokens: Int): Encoding
}

/** [attentionMask] holds 1 for every id of [inputIds]: an encoding is never padded. */
class Encoding(val inputIds: IntArray, val attentionMask: IntArray) {
    val size: Int get() = inputIds.size
}
