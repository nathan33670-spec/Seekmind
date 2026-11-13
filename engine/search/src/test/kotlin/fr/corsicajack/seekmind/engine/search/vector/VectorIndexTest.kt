package fr.corsicajack.seekmind.engine.search.vector

import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.model.SearchFilters
import fr.corsicajack.seekmind.core.model.TimeRange
import fr.corsicajack.seekmind.core.model.config.EmbeddingConfig
import fr.corsicajack.seekmind.core.model.config.SearchConfig
import fr.corsicajack.seekmind.core.testing.TestVectors
import fr.corsicajack.seekmind.engine.embedding.codec.VectorCodec
import kotlin.math.sqrt
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test

class VectorIndexTest {
    private val codec = VectorCodec(EmbeddingConfig().int8Scale)
    private val store = FakeVectorStore(codec)

    /** No floor: random vectors are far from each other. */
    private fun TestScope.index(config: SearchConfig = SearchConfig(semanticFloor = -1f)) =
        VectorIndex(store, config, codec, StandardTestDispatcher(testScheduler))

    @Test
    fun theNearestCaptureComesFirst() = runTest {
        val query = TestVectors.unit(1)
        store.add(10, TestVectors.unit(2))
        store.add(11, near(query, 0.1f, seed = 3))
        store.add(12, TestVectors.unit(4))
        val hits = index().search(query, SearchFilters())
        assertThat(hits.first().captureId).isEqualTo(11)
        assertThat(hits.first().similarity.toDouble()).isWithin(0.02).of(dot(query, store.rows[1].vector))
        assertThat(hits.map { it.captureId }).containsExactly(10L, 11L, 12L)
    }

    @Test
    fun theFloorRejectsDistantVectors() = runTest {
        val query = TestVectors.unit(1)
        store.add(10, near(query, 0.1f, seed = 3))
        store.add(11, TestVectors.unit(4))
        val hits = index(SearchConfig(semanticFloor = 0.78f)).search(query, SearchFilters())
        assertThat(hits.map { it.captureId }).containsExactly(10L)
    }

    @Test
    fun eachCaptureKeepsItsBestFragment() = runTest {
        val query = TestVectors.unit(1)
        store.add(10, near(query, 0.6f, seed = 5))
        val best = store.add(10, near(query, 0.05f, seed = 6))
        store.add(11, near(query, 0.3f, seed = 7))
        val hits = index().search(query, SearchFilters())
        assertThat(hits.map { it.captureId }).containsExactly(10L, 11L).inOrder()
        assertThat(hits.first().embeddingId).isEqualTo(best)
    }

    @Test
    fun atMostSemanticTopKCaptures() = runTest {
        repeat(50) { store.add(it.toLong(), TestVectors.unit(100L + it)) }
        val hits = index(
            SearchConfig(semanticFloor = -1f, semanticTopK = 7),
        ).search(TestVectors.unit(1), SearchFilters())
        assertThat(hits).hasSize(7)
        assertThat(hits.map { it.similarity }).isInOrder(Comparator.reverseOrder<Float>())
    }

    @Test
    fun theDateFilterIsAppliedDuringTheScan() = runTest {
        val query = TestVectors.unit(1)
        // The closest vector is outside the range: it must not take a preselection slot.
        store.add(10, query, first = 0, last = 999)
        store.add(11, near(query, 0.5f, seed = 2), first = 500, last = 1_500)
        store.add(12, near(query, 0.5f, seed = 3), first = 2_000, last = 3_000)
        store.add(13, near(query, 0.5f, seed = 4), first = 3_001, last = 4_000)
        val index = index(SearchConfig(semanticFloor = -1f, binaryTopK = 2))
        val hits = index.search(query, SearchFilters(range = TimeRange(1_000, 3_000)))
        assertThat(hits.map { it.captureId }).containsExactly(11L, 12L)
    }

    @Test
    fun thePackageFilterIsAppliedDuringTheScan() = runTest {
        val query = TestVectors.unit(1)
        store.add(10, query, packageName = "com.example.chat")
        store.add(11, near(query, 0.5f, seed = 2), packageName = "com.example.notes")
        store.add(12, near(query, 0.5f, seed = 3), packageName = "com.example.mail")
        store.add(13, near(query, 0.5f, seed = 4), packageName = "com.example.notes")
        val index = index(SearchConfig(semanticFloor = -1f, binaryTopK = 2))
        val notes = index.search(query, SearchFilters(packages = setOf("com.example.notes")))
        assertThat(notes.map { it.captureId }).containsExactly(11L, 13L)
        assertThat(index.search(query, SearchFilters(packages = setOf("com.example.absent")))).isEmpty()
        assertThat(
            index.search(query, SearchFilters(packages = setOf("com.example.chat", "com.example.mail"))).map {
                it.captureId
            },
        )
            .containsExactly(10L, 12L)
    }

    @Test
    fun anAddedVectorAppearsInTheNextSearch() = runTest {
        val query = TestVectors.unit(1)
        store.add(10, TestVectors.unit(2))
        val index = index()
        assertThat(index.search(query, SearchFilters()).map { it.captureId }).containsExactly(10L)
        store.add(11, near(query, 0.1f, seed = 3))
        assertThat(index.search(query, SearchFilters()).first().captureId).isEqualTo(11)
        assertThat(index.size()).isEqualTo(2)
    }

    @Test
    fun aDeletionReloadsTheIndex() = runTest {
        val query = TestVectors.unit(1)
        store.add(10, near(query, 0.1f, seed = 3))
        store.add(11, TestVectors.unit(2))
        val index = index()
        index.search(query, SearchFilters())
        store.deleteCapture(10)
        assertThat(index.search(query, SearchFilters()).map { it.captureId }).containsExactly(11L)
        assertThat(index.size()).isEqualTo(1)
    }

    @Test
    fun loadingIsLazyAndPaginated() = runTest {
        repeat(25) { store.add(it.toLong(), TestVectors.unit(100L + it)) }
        val index = index(SearchConfig(semanticFloor = -1f, indexPageSize = 10))
        assertThat(store.pageReads).isEqualTo(0)
        index.refresh()
        assertThat(store.pageReads).isEqualTo(3) // 10, 10, 5
        assertThat(index.size()).isEqualTo(25)
        index.search(TestVectors.unit(1), SearchFilters())
        assertThat(store.pageReads).isEqualTo(4) // one read for the vectors added since
    }

    @Test
    fun releaseFreesTheIndexAndTheNextSearchReloadsIt() = runTest {
        store.add(10, TestVectors.unit(2))
        val index = index()
        index.refresh()
        index.release()
        assertThat(index.size()).isEqualTo(0)
        assertThat(index.search(TestVectors.unit(1), SearchFilters()).map { it.captureId }).containsExactly(10L)
    }

    @Test
    fun anEmptyIndexFindsNothingWithoutReadingVectors() = runTest {
        val index = index()
        assertThat(index.isEmpty()).isTrue()
        assertThat(index.search(TestVectors.unit(1), SearchFilters())).isEmpty()
        assertThat(store.int8Reads).isEqualTo(0)
    }

    @Test
    fun theRescoreUsesTheInt8VectorsInMemory() = runTest {
        val query = TestVectors.unit(1)
        repeat(30) { store.add(it.toLong(), TestVectors.unit(100L + it)) }
        val index = index()
        val hits = index.search(query, SearchFilters())
        index.search(TestVectors.unit(2), SearchFilters())
        assertThat(store.int8Reads).isEqualTo(0)
        for (hit in hits) {
            val stored = store.rows.single { it.id == hit.embeddingId }.vector
            assertThat(hit.similarity).isWithin(1e-6f).of(codec.rescore(query, codec.int8(stored)))
        }
    }

    @Test
    fun onEqualDistanceTheNewestVectorIsKept() {
        val codes = BinaryCodes(6)
        val code = LongArray(6) { 0x5555_5555_5555_5555L }
        repeat(5) { codes.add(it + 1L, it.toLong(), 0, 0, "p", code, ByteArray(384)) }
        val into = IntArray(2)
        val distances = IntArray(2)
        assertThat(codes.preselect(code, null, null, null, into, distances)).isEqualTo(2)
        assertThat(into.sorted()).containsExactly(3, 4)
    }

    companion object {
        /** Unit vector at roughly [noise] from [base]. */
        fun near(base: FloatArray, noise: Float, seed: Long): FloatArray {
            val other = TestVectors.unit(seed)
            val mixed = FloatArray(base.size) { base[it] + noise * other[it] }
            val norm = sqrt(mixed.sumOf { (it * it).toDouble() }).toFloat()
            return FloatArray(mixed.size) { mixed[it] / norm }
        }

        fun dot(a: FloatArray, b: FloatArray): Double = a.indices.sumOf { (a[it] * b[it]).toDouble() }
    }
}
