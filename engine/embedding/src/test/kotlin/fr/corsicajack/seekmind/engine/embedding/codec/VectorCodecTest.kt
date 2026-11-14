package fr.corsicajack.seekmind.engine.embedding.codec

import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.model.config.EmbeddingConfig
import fr.corsicajack.seekmind.core.testing.TestVectors
import org.junit.Assert.assertThrows
import org.junit.Test

class VectorCodecTest {
    private val codec = VectorCodec(EmbeddingConfig().int8Scale)

    @Test
    fun int8RoundTripStaysWithinHalfAStep() {
        val vector = TestVectors.unit(seed = 7)
        val decoded = codec.decodeInt8(codec.int8(vector))
        for (i in vector.indices) assertThat(decoded[i]).isWithin(0.5f / codec.scale + 1e-7f).of(vector[i])
    }

    @Test
    fun int8IsTheRoundedScaledComponent() {
        val vector = FloatArray(384)
        vector[0] = 0.05f // 20
        vector[1] = -0.0101f // -4.04 → -4
        vector[2] = 0.00125f // 0.5 → 0, half to even
        vector[3] = 0.00375f // 1.5 → 2
        vector[4] = -0.00125f // -0.5 → 0, the opposite of the code of 0.5
        val code = codec.int8(vector)
        assertThat(
            code.take(5),
        ).containsExactly(20.toByte(), (-4).toByte(), 0.toByte(), 2.toByte(), 0.toByte()).inOrder()
    }

    @Test
    fun componentsOutsideTheRangeAreClipped() {
        val vector = FloatArray(384)
        vector[0] = 0.9f
        vector[1] = -0.9f
        vector[2] = 127.4f / 400f
        vector[3] = -127.6f / 400f
        val code = codec.int8(vector)
        assertThat(code.take(4)).containsExactly(127.toByte(), (-127).toByte(), 127.toByte(), (-127).toByte()).inOrder()
    }

    @Test
    fun rescoreApproximatesTheCosine() {
        val query = TestVectors.unit(seed = 1)
        val near = FloatArray(384) { query[it] * 0.9f + TestVectors.unit(seed = 2)[it] * 0.1f }.normalized()
        val far = TestVectors.unit(seed = 3)
        for (document in listOf(query, near, far)) {
            val exact = query.indices.sumOf { (query[it] * document[it]).toDouble() }
            assertThat(codec.rescore(query, codec.int8(document)).toDouble()).isWithin(0.01).of(exact)
        }
    }

    @Test
    fun binaryCodeSetsBitIForPositiveComponentI() {
        val vector = FloatArray(384) { -0.01f }
        val positive = listOf(0, 7, 8, 63, 64, 65, 200, 383)
        for (i in positive) vector[i] = 0.01f
        vector[100] = 0f // strictly positive only
        val code = codec.binary(vector)
        assertThat(code.size).isEqualTo(48)
        for (i in 0 until 384) {
            val bit = (code[i / 8].toInt() ushr (i % 8)) and 1
            assertThat(bit).isEqualTo(if (i in positive) 1 else 0)
        }
        val words = VectorCodec.words(code)
        assertThat(words.size).isEqualTo(6)
        assertThat(words[0]).isEqualTo((1L shl 0) or (1L shl 7) or (1L shl 8) or (1L shl 63))
        assertThat(words[1]).isEqualTo((1L shl 0) or (1L shl 1))
        assertThat(words[3]).isEqualTo(1L shl (200 - 192))
        assertThat(words[5]).isEqualTo(1L shl 63)
        assertThat(words[2]).isEqualTo(0L)
        assertThat(words[4]).isEqualTo(0L)
    }

    @Test
    fun matchesTheEncodingOfTheTestVectors() {
        for (seed in 1L..50L) {
            val vector = TestVectors.unit(seed)
            assertThat(codec.binary(vector)).isEqualTo(TestVectors.binary(vector))
            assertThat(codec.int8(vector)).isEqualTo(TestVectors.int8(vector))
        }
    }

    @Test
    fun wordsAreLittleEndian() {
        val code = ByteArray(48)
        code[0] = 0x01
        code[7] = 0x80.toByte()
        assertThat(VectorCodec.words(code)[0]).isEqualTo(Long.MIN_VALUE or 1L)
    }

    @Test
    fun wrongSizesAreRefused() {
        assertThrows(IllegalArgumentException::class.java) { codec.int8(FloatArray(383)) }
        assertThrows(IllegalArgumentException::class.java) { codec.binary(FloatArray(385)) }
        assertThrows(IllegalArgumentException::class.java) { codec.rescore(FloatArray(384), ByteArray(48)) }
        assertThrows(IllegalArgumentException::class.java) { VectorCodec(0f) }
    }

    private fun FloatArray.normalized(): FloatArray {
        val norm = kotlin.math.sqrt(sumOf { (it * it).toDouble() }).toFloat()
        return FloatArray(size) { this[it] / norm }
    }
}
