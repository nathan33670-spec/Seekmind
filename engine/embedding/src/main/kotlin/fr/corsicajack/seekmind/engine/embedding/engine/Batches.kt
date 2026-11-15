package fr.corsicajack.seekmind.engine.embedding.engine

import fr.corsicajack.seekmind.engine.embedding.tokenizer.Encoding
import kotlin.math.sqrt

/**
 * A sub-batch ready for the encoder (docs/specs/embeddings.md §4): [rows] encodings padded with `<pad>`
 * to the [length] of the longest, row-major, the mask at 0 on the padding.
 */
class PaddedBatch(val rows: Int, val length: Int, val inputIds: LongArray, val attentionMask: LongArray) {
    companion object {
        const val PAD_ID = 1L

        fun of(encodings: List<Encoding>): PaddedBatch {
            require(encodings.isNotEmpty()) { "empty batch" }
            val length = encodings.maxOf { it.size }
            val ids = LongArray(encodings.size * length) { PAD_ID }
            val mask = LongArray(encodings.size * length)
            encodings.forEachIndexed { row, encoding ->
                for (t in 0 until encoding.size) {
                    ids[row * length + t] = encoding.inputIds[t].toLong()
                    mask[row * length + t] = encoding.attentionMask[t].toLong()
                }
            }
            return PaddedBatch(encodings.size, length, ids, mask)
        }
    }
}

/** Splits [encodings] into sub-batches of texts of similar lengths, and gives back the original order. */
internal object Batching {
    /** Indices of [encodings], shortest first (stable), in groups of at most [size]. */
    fun plan(encodings: List<Encoding>, size: Int): List<List<Int>> {
        require(size > 0) { "sub-batch size must be positive: $size" }
        return encodings.indices.sortedBy { encodings[it].size }.chunked(size)
    }
}

/** Mean of the hidden states weighted by the attention mask, then L2 normalisation (docs/specs/embeddings.md §4). */
internal object Pooling {
    /**
     * [hidden] is the `last_hidden_state` of [batch], row-major: `rows × length × dimensions`.
     * Gives one unit vector per row.
     */
    fun maskedMeanNormalized(hidden: FloatArray, batch: PaddedBatch, dimensions: Int): List<FloatArray> {
        require(hidden.size == batch.rows * batch.length * dimensions) {
            "hidden state of ${hidden.size} values for ${batch.rows} × ${batch.length} × $dimensions"
        }
        return List(batch.rows) { row ->
            val sum = DoubleArray(dimensions)
            var weight = 0L
            for (t in 0 until batch.length) {
                val mask = batch.attentionMask[row * batch.length + t]
                if (mask == 0L) continue
                weight += mask
                val offset = (row * batch.length + t) * dimensions
                for (d in 0 until dimensions) sum[d] += hidden[offset + d].toDouble() * mask
            }
            check(weight > 0) { "row $row has no token" }
            var squares = 0.0
            for (d in 0 until dimensions) {
                sum[d] /= weight
                squares += sum[d] * sum[d]
            }
            val norm = sqrt(squares)
            FloatArray(dimensions) { if (norm == 0.0) 0f else (sum[it] / norm).toFloat() }
        }
    }
}
