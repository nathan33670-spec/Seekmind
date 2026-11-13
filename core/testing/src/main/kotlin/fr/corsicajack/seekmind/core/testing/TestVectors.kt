package fr.corsicajack.seekmind.core.testing

import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Pseudo-random unit vectors and their stored forms, for tests and synthetic databases. The int8 and
 * binary encodings follow docs/specs/embeddings.md §6 with the starting scale S = 400; the production
 * codec (VectorCodec of engine:embedding, M4-04) owns them; it rounds ties to even, which random
 * vectors never hit.
 */
object TestVectors {
    const val DIMENSIONS = 384
    const val SCALE = 400f

    /** A unit vector drawn from [seed]: the same seed, the same vector. */
    fun unit(seed: Long, dimensions: Int = DIMENSIONS): FloatArray {
        val random = Random(seed)
        val values = FloatArray(dimensions) { (random.nextDouble() * 2 - 1).toFloat() }
        val norm = sqrt(values.sumOf { (it * it).toDouble() }).toFloat()
        return FloatArray(dimensions) { values[it] / norm }
    }

    fun int8(vector: FloatArray): ByteArray = ByteArray(vector.size) {
        (vector[it] * SCALE).roundToInt().coerceIn(-127, 127).toByte()
    }

    /** Bit i set when component i is positive; 64-bit words, little-endian. */
    fun binary(vector: FloatArray): ByteArray {
        val bytes = ByteArray(vector.size / 8)
        for (i in vector.indices) if (vector[i] > 0) bytes[i / 8] = (bytes[i / 8].toInt() or (1 shl (i % 8))).toByte()
        return bytes
    }
}
