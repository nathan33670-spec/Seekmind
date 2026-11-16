package fr.corsicajack.seekmind.engine.embedding.codec

import kotlin.math.round

/**
 * Stored forms of a normalised vector (docs/specs/embeddings.md §6), common to every model.
 *
 * - int8: `clamp(round(v[i] × scale), −127, 127)`, one byte per component, for the precise rescore;
 * - binary: bit `i` set when `v[i] > 0`, packed in 64-bit little-endian words, for the Hamming preselection.
 *
 * `round` rounds half to even, so that the code of `−v` is the opposite of the code of `v`.
 */
class VectorCodec(val scale: Float, val dimensions: Int = DIMENSIONS) {
    init {
        require(scale > 0f) { "scale must be positive: $scale" }
        require(dimensions > 0 && dimensions % Long.SIZE_BITS == 0) {
            "dimensions must be a multiple of 64: $dimensions"
        }
    }

    /** Bytes of a binary code: 48 for 384 dimensions. */
    val binarySize: Int get() = dimensions / Byte.SIZE_BITS

    fun int8(vector: FloatArray): ByteArray {
        checkSize(vector.size)
        return ByteArray(dimensions) { round(vector[it] * scale).coerceIn(-INT8_LIMIT, INT8_LIMIT).toInt().toByte() }
    }

    /** Approximation of the vector an int8 code was made from. */
    fun decodeInt8(code: ByteArray): FloatArray {
        checkSize(code.size)
        return FloatArray(dimensions) { code[it] / scale }
    }

    /** `(Σ query[i] × document[i]) / scale`: approximates the cosine of two normalised vectors. */
    fun rescore(query: FloatArray, document: ByteArray): Float {
        checkSize(query.size)
        checkSize(document.size)
        var sum = 0f
        for (i in 0 until dimensions) sum += query[i] * document[i]
        return sum / scale
    }

    /** Bit `i` lives in bit `i % 8` of byte `i / 8`: the bytes of little-endian 64-bit words. */
    fun binary(vector: FloatArray): ByteArray {
        checkSize(vector.size)
        val code = ByteArray(binarySize)
        for (i in 0 until dimensions) {
            if (vector[i] > 0f) code[i ushr 3] = (code[i ushr 3].toInt() or (1 shl (i and 7))).toByte()
        }
        return code
    }

    private fun checkSize(size: Int) = require(size == dimensions) { "expected $dimensions components, got $size" }

    companion object {
        const val DIMENSIONS = 384
        private const val INT8_LIMIT = 127f

        /** The 64-bit words of a binary code, word `w` holding bits `64w` to `64w + 63`. */
        fun words(code: ByteArray): LongArray {
            require(code.size % Long.SIZE_BYTES == 0) { "a binary code holds whole 64-bit words: ${code.size} bytes" }
            return LongArray(code.size / Long.SIZE_BYTES) { word ->
                var value = 0L
                for (byte in 0 until Long.SIZE_BYTES) {
                    value =
                        value or ((code[word * Long.SIZE_BYTES + byte].toLong() and 0xFF) shl (byte * Byte.SIZE_BITS))
                }
                value
            }
        }
    }
}
