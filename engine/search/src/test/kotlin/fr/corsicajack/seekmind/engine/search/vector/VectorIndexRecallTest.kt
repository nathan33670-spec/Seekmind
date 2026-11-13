package fr.corsicajack.seekmind.engine.search.vector

import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.model.SearchFilters
import fr.corsicajack.seekmind.core.model.config.EmbeddingConfig
import fr.corsicajack.seekmind.core.model.config.SearchConfig
import fr.corsicajack.seekmind.core.testing.TestVectors
import fr.corsicajack.seekmind.engine.embedding.codec.VectorCodec
import java.lang.management.ManagementFactory
import kotlin.math.sqrt
import kotlin.random.Random
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Ticket M5-05: recall of the 10 exact nearest neighbours among the results (preselection of 200 by
 * Hamming distance, then int8 rescore) on 20 000 random unit vectors, and no allocation per vector during
 * the scan. The measures are printed and recorded in docs/specs/recherche.md §6.
 */
class VectorIndexRecallTest {
    private val codec = VectorCodec(EmbeddingConfig().int8Scale)
    private val config = SearchConfig(semanticFloor = -1f)

    /**
     * Vectors grouped around 2 000 random centres (10 per centre), queries drawn near a centre: the shape
     * of a corpus where a query has a few close documents. Target ≥ 0.90.
     */
    @Test
    fun recallOnClusteredVectors() = runTest {
        val random = Random(42)
        val centres = List(CLUSTERS) { TestVectors.unit(1_000_000L + it) }
        val vectors = List(COUNT) { perturb(centres[it % CLUSTERS], CLUSTER_SPREAD, random) }
        val queries = List(QUERIES) { perturb(centres[random.nextInt(CLUSTERS)], CLUSTER_SPREAD, random) }
        val recall = recall(vectors, queries)
        println("m5_05_recall_at_10_clustered=$recall")
        assertThat(recall).isAtLeast(0.90)
    }

    /**
     * Isotropic random vectors, the worst case for a sign code: every vector is nearly orthogonal to the
     * query, the 10 nearest stand out little. Measured, not held to the target.
     */
    @Test
    fun recallOnIsotropicVectorsIsMeasured() = runTest {
        val vectors = List(COUNT) { TestVectors.unit(it.toLong()) }
        val queries = List(QUERIES) { TestVectors.unit(5_000_000L + it) }
        val recall = recall(vectors, queries)
        println("m5_05_recall_at_10_isotropic=$recall")
        assertThat(recall).isGreaterThan(0.0)
    }

    /**
     * The shape of the e5 vectors: a direction shared by every text (similarities of 0.7 to 0.9 even between
     * unrelated texts, docs/specs/recherche.md §6.2), plus the clusters above. The sign code loses more
     * here: 200 candidates fell short of the target, hence the default of 400 (project owner, 13 November
     * 2026); 200 is still measured for the record. Calibrated on real vectors by ticket M5-07.
     */
    @Test
    fun recallOnAnisotropicClusteredVectors() = runTest {
        val random = Random(7)
        val shared = TestVectors.unit(9_999_999L)
        val centres = List(CLUSTERS) { mix(shared, SHARED_WEIGHT, TestVectors.unit(2_000_000L + it)) }
        val vectors = List(COUNT) { perturb(centres[it % CLUSTERS], ANISOTROPIC_SPREAD, random) }
        val queries = List(QUERIES) { perturb(centres[random.nextInt(CLUSTERS)], ANISOTROPIC_SPREAD, random) }
        println(
            "m5_05_mean_similarity_anisotropic=${(0 until 200).map {
                dot(vectors[it], vectors[it + 7_001])
            }.average()}",
        )
        val default = recall(vectors, queries)
        val half = recall(vectors, queries, binaryTopK = config.binaryTopK / 2)
        println("m5_05_recall_at_10_anisotropic_top${config.binaryTopK}=$default")
        println("m5_05_recall_at_10_anisotropic_top${config.binaryTopK / 2}=$half")
        assertThat(default).isAtLeast(0.90)
    }

    @Test
    fun theScanAllocatesNothingPerVector() {
        val codes = BinaryCodes(WORDS)
        repeat(COUNT) { i ->
            val code = VectorCodec.words(codec.binary(TestVectors.unit(i.toLong())))
            codes.add(i + 1L, i.toLong(), i.toLong(), i + 10L, "p${i % 20}", code, ByteArray(384))
        }
        val query = VectorCodec.words(codec.binary(TestVectors.unit(-1)))
        val mask = codes.packageMask(SearchFilters(packages = setOf("p1", "p2", "p3")))
        val into = IntArray(config.binaryTopK)
        val distances = IntArray(config.binaryTopK)
        repeat(20) { codes.preselect(query, 100, 15_000, mask, into, distances) } // JIT
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val thread = Thread.currentThread().id
        val before = threads.getThreadAllocatedBytes(thread)
        repeat(10) { codes.preselect(query, 100, 15_000, mask, into, distances) }
        val allocated = threads.getThreadAllocatedBytes(thread) - before
        println("m5_05_scan_bytes_allocated_for_10_scans_of_$COUNT=$allocated")
        // A single object per vector would already be 10 × 20 000 × 16 bytes.
        assertThat(allocated).isLessThan(16_384L)
    }

    private suspend fun kotlinx.coroutines.test.TestScope.recall(
        vectors: List<FloatArray>,
        queries: List<FloatArray>,
        binaryTopK: Int = config.binaryTopK,
    ): Double {
        val store = FakeVectorStore(codec)
        vectors.forEachIndexed { i, v -> store.add(i.toLong(), v) }
        val index =
            VectorIndex(
                store,
                config.copy(semanticTopK = NEIGHBOURS, binaryTopK = binaryTopK),
                codec,
                StandardTestDispatcher(testScheduler),
            )
        var found = 0
        for (query in queries) {
            // One score per vector, computed once: a selector inside the sort would run at every comparison.
            val scores = DoubleArray(vectors.size) { dot(query, vectors[it]) }
            val exact = vectors.indices.sortedByDescending { scores[it] }.take(NEIGHBOURS).map { it.toLong() }.toSet()
            val hits = index.search(query, SearchFilters()).map { it.captureId }
            found += hits.count { it in exact }
        }
        return found.toDouble() / (queries.size * NEIGHBOURS)
    }

    private companion object {
        const val COUNT = 20_000
        const val CLUSTERS = 2_000
        const val QUERIES = 100
        const val NEIGHBOURS = 10
        const val WORDS = 6

        /** Noise added to a centre, relative to its norm of 1. */
        const val CLUSTER_SPREAD = 1.2f

        fun perturb(centre: FloatArray, spread: Float, random: Random): FloatArray {
            val noise = FloatArray(centre.size) { (random.nextDouble() * 2 - 1).toFloat() }
            val noiseNorm = sqrt(noise.sumOf { (it * it).toDouble() }).toFloat()
            val mixed = FloatArray(centre.size) { centre[it] + spread * noise[it] / noiseNorm }
            val norm = sqrt(mixed.sumOf { (it * it).toDouble() }).toFloat()
            return FloatArray(centre.size) { mixed[it] / norm }
        }

        /** Weight of the shared direction: similarities around 0.8 between unrelated vectors. */
        const val SHARED_WEIGHT = 2.0f
        const val ANISOTROPIC_SPREAD = 0.5f

        fun mix(shared: FloatArray, weight: Float, own: FloatArray): FloatArray {
            val mixed = FloatArray(own.size) { weight * shared[it] + own[it] }
            val norm = sqrt(mixed.sumOf { (it * it).toDouble() }).toFloat()
            return FloatArray(own.size) { mixed[it] / norm }
        }

        fun dot(a: FloatArray, b: FloatArray): Double {
            var sum = 0.0
            for (i in a.indices) sum += a[i] * b[i]
            return sum
        }
    }
}
