package fr.corsicajack.seekmind.engine.embedding.engine

import ai.onnxruntime.NodeInfo
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.nio.LongBuffer

/**
 * [Encoder] on an ONNX Runtime session (docs/specs/embeddings.md §4). Feeds the inputs the session
 * declares: `input_ids`, `attention_mask`, and `token_type_ids` at zero when the model asks for it.
 */
class OrtEncoder(private val session: OrtSession) : Encoder {
    private val environment = OrtEnvironment.getEnvironment()
    private val inputs: List<String> = session.inputNames.toList()
    private val output: String

    override val dimensions: Int

    init {
        val unknown = inputs - KNOWN_INPUTS
        require(unknown.isEmpty()) { "unexpected model inputs: $unknown" }
        require(INPUT_IDS in inputs && ATTENTION_MASK in inputs) { "model inputs: $inputs" }
        output = if (HIDDEN_STATE in session.outputNames) HIDDEN_STATE else session.outputNames.first()
        val shape = ((session.outputInfo.getValue(output) as NodeInfo).info as TensorInfo).shape
        require(shape.size == 3 && shape[2] > 0) { "unexpected output shape ${shape.toList()}" }
        dimensions = shape[2].toInt()
    }

    override fun encode(batch: PaddedBatch): FloatArray {
        val shape = longArrayOf(batch.rows.toLong(), batch.length.toLong())
        val feeds = LinkedHashMap<String, OnnxTensor>()
        try {
            for (name in inputs) {
                val values = when (name) {
                    INPUT_IDS -> batch.inputIds
                    ATTENTION_MASK -> batch.attentionMask
                    else -> LongArray(batch.inputIds.size)
                }
                feeds[name] = OnnxTensor.createTensor(environment, LongBuffer.wrap(values), shape)
            }
            session.run(feeds, setOf(output)).use { result ->
                val tensor = result.get(output).orElseThrow() as OnnxTensor
                val buffer = tensor.floatBuffer
                return FloatArray(buffer.remaining()).also { buffer.get(it) }
            }
        } finally {
            feeds.values.forEach { it.close() }
        }
    }

    override fun close() = session.close()

    private companion object {
        const val INPUT_IDS = "input_ids"
        const val ATTENTION_MASK = "attention_mask"
        const val TOKEN_TYPE_IDS = "token_type_ids"
        const val HIDDEN_STATE = "last_hidden_state"
        val KNOWN_INPUTS = setOf(INPUT_IDS, ATTENTION_MASK, TOKEN_TYPE_IDS)
    }
}
