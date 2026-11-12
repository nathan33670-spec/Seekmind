package fr.corsicajack.seekmind.core.testing

import com.google.common.truth.Truth.assertThat
import kotlin.math.sqrt
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DoublesTest {
    @Test
    fun `fake embeddings are deterministic, unit length and prefixed`() = runTest {
        val engine = FakeEmbeddingEngine()
        val a = engine.embedPassages(listOf("facture d'électricité")).single()
        val b = FakeEmbeddingEngine().embedPassages(listOf("facture d'électricité")).single()
        val query = engine.embedQuery("facture d'électricité")
        assertThat(a.toList()).isEqualTo(b.toList())
        assertThat(norm(a)).isWithin(1e-5).of(1.0)
        assertThat(a.size).isEqualTo(384)
        assertThat(query.toList()).isNotEqualTo(a.toList()) // the prefix is part of the text
        assertThat(
            engine.received,
        ).containsExactly("passage: facture d'électricité", "query: facture d'électricité").inOrder()
    }

    @Test
    fun `a closed fake engine refuses to work`() = runTest {
        val engine = FakeEmbeddingEngine()
        engine.close()
        assertThat(runCatching { engine.embedQuery("x") }.isFailure).isTrue()
    }

    @Test
    fun `vector encodings`() {
        val vector = FloatArray(384) { if (it % 2 == 0) 0.5f else -0.001f }
        val int8 = TestVectors.int8(vector)
        assertThat(int8[0]).isEqualTo(127.toByte()) // 0.5 × 400 = 200, clamped
        assertThat(int8[1]).isEqualTo(0.toByte())
        val binary = TestVectors.binary(vector)
        assertThat(binary).hasLength(48)
        assertThat(binary[0]).isEqualTo(0b01010101.toByte())
        assertThat(norm(TestVectors.unit(7))).isWithin(1e-5).of(1.0)
        assertThat(TestVectors.unit(7).toList()).isEqualTo(TestVectors.unit(7).toList())
    }

    @Test
    fun `the fake clock moves by hand`() {
        val clock = FakeClock(now = 1_000, elapsed = 10)
        clock.advance(500)
        assertThat(clock.nowMillis()).isEqualTo(1_500)
        assertThat(clock.elapsedRealtime()).isEqualTo(510)
    }

    @Test
    fun `the synthetic corpus is deterministic and plausible`() {
        val corpus = SyntheticCorpus()
        val captures = (0 until 2_000).map(corpus::capture)
        assertThat(captures.take(50)).isEqualTo((0 until 50).map(SyntheticCorpus()::capture))
        assertThat(captures.all { it.text.length in 20..900 }).isTrue()
        assertThat(captures.map { it.packageName }.toSet()).hasSize(6)
        assertThat(captures.all { it.lastSeenAt in Models.AT - 90 * 86_400_000L..Models.AT }).isTrue()
        assertThat(captures.count { it.url != null }).isGreaterThan(0)
        assertThat(captures.map { it.text }.toSet().size).isGreaterThan(1_900)
    }

    private fun norm(v: FloatArray) = sqrt(v.sumOf { (it * it).toDouble() })
}
