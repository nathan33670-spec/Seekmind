package fr.corsicajack.seekmind.engine.embedding.engine

/** Runs the transformer on a padded sub-batch: the ONNX Runtime session, or a test double. */
interface Encoder : AutoCloseable {
    /** Size of a hidden state: 384. */
    val dimensions: Int

    /** `last_hidden_state` of [batch], row-major: `rows × length × dimensions`. */
    fun encode(batch: PaddedBatch): FloatArray
}
