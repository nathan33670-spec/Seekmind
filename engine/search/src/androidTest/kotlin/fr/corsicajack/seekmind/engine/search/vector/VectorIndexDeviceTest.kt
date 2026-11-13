package fr.corsicajack.seekmind.engine.search.vector

import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.database.DatabaseHandle
import fr.corsicajack.seekmind.core.database.store.NewChunk
import fr.corsicajack.seekmind.core.database.store.NewEmbedding
import fr.corsicajack.seekmind.core.model.SearchFilters
import fr.corsicajack.seekmind.core.model.TimeRange
import fr.corsicajack.seekmind.core.model.config.EmbeddingConfig
import fr.corsicajack.seekmind.core.model.config.SearchConfig
import fr.corsicajack.seekmind.core.testing.FakeEmbeddingEngine
import fr.corsicajack.seekmind.core.testing.Models
import fr.corsicajack.seekmind.core.testing.SyntheticDatabase
import fr.corsicajack.seekmind.core.testing.TemporaryDatabase
import fr.corsicajack.seekmind.core.testing.TestVectors
import fr.corsicajack.seekmind.engine.embedding.codec.VectorCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** [VectorIndex] on the real encrypted database, with [FakeEmbeddingEngine] (docs/specs/recherche.md §12). */
@RunWith(AndroidJUnit4::class)
class VectorIndexDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val database = TemporaryDatabase(context, "vector_index")
    private val codec = VectorCodec(EmbeddingConfig().int8Scale)
    private val engine = FakeEmbeddingEngine()
    private lateinit var handle: DatabaseHandle
    private lateinit var index: VectorIndex

    @Before
    fun setUp() {
        handle = database.open()
        // The fake engine's vectors are unrelated: no floor, so that every candidate is returned.
        index = VectorIndex(handle.vectors, SearchConfig(semanticFloor = -1f), codec, Dispatchers.Default)
    }

    @After
    fun tearDown() = database.delete()

    /** One capture of one fragment, embedded as a passage of [text]; returns the capture id. */
    private suspend fun capture(text: String, at: Long, packageName: String = "com.example.notes"): Long {
        val id = handle.captures.insert(
            Models.newCapture(text, at = at, packageName = packageName),
            listOf(NewChunk(0, 0, text.length)),
        )
        val chunk = handle.vectors.pendingChunks(100).single { it.captureId == id }
        val vector = engine.embedPassages(listOf(text)).single()
        handle.vectors.saveEmbeddings(
            listOf(NewEmbedding(chunk.chunkId, id, engine.modelId, codec.int8(vector), codec.binary(vector))),
        )
        return id
    }

    @Test
    fun findsAddsFiltersAndReloadsAfterADeletion() = runBlocking {
        val query = engine.embedPassages(listOf("horaires de la piscine")).single()
        val pool = capture("horaires de la piscine", at = 1_000)
        val other = capture("recette de la tarte aux pommes", at = 5_000, packageName = "com.example.recipes")
        assertThat(index.search(query, SearchFilters()).first().captureId).isEqualTo(pool)
        assertThat(index.search(query, SearchFilters()).first().similarity).isWithin(0.01f).of(1f)

        val added = capture("horaires de la piscine", at = 9_000, packageName = "com.example.chat")
        val hits = index.search(query, SearchFilters())
        assertThat(hits.map { it.captureId }).containsAtLeast(pool, other, added)
        assertThat(hits.first().captureId).isEqualTo(added) // same similarity, seen more recently

        assertThat(index.search(query, SearchFilters(range = TimeRange(4_000, 6_000))).map { it.captureId })
            .containsExactly(other)
        assertThat(index.search(query, SearchFilters(packages = setOf("com.example.notes"))).map { it.captureId })
            .containsExactly(pool)

        handle.captures.delete(added)
        assertThat(index.search(query, SearchFilters()).map { it.captureId }).containsExactly(pool, other)
        assertThat(index.size()).isEqualTo(2)
    }

    /**
     * Cost of the rescore with 400 candidates, the default, against 200 (docs/specs/recherche.md §6.3):
     * 20 000 vectors in the encrypted database, loading of the index, then the median of 15 searches.
     * Logged under `SeekMindBench`; ENF-PERF-04 allows 30 ms for the whole search, query embedding aside.
     */
    @Test
    fun measuresTheSearchWith200And400Candidates() = runBlocking {
        SyntheticDatabase(handle).populate(VECTORS, vectors = true)
        for (topK in listOf(400, 200)) {
            val index =
                VectorIndex(
                    handle.vectors,
                    SearchConfig(binaryTopK = topK, semanticFloor = -1f),
                    codec,
                    Dispatchers.Default,
                )
            val loadStart = SystemClock.elapsedRealtimeNanos()
            index.refresh()
            val loadMs = (SystemClock.elapsedRealtimeNanos() - loadStart) / 1e6
            assertThat(index.size()).isEqualTo(VECTORS)
            val queries = List(WARMUP + RUNS) { TestVectors.unit(seed = 1_000_000L + it) }
            val times = queries.mapIndexed { i, query ->
                val start = SystemClock.elapsedRealtimeNanos()
                val hits = index.search(query, SearchFilters())
                assertThat(hits).isNotEmpty()
                if (i < WARMUP) null else (SystemClock.elapsedRealtimeNanos() - start) / 1e6
            }.filterNotNull().sorted()
            Log.i(TAG, "api=${Build.VERSION.SDK_INT} m5_05_index_load_${VECTORS}_ms=$loadMs")
            Log.i(
                TAG,
                "api=${Build.VERSION.SDK_INT} m5_05_search_${VECTORS}_top${topK}_ms_median=${times[times.size / 2]}",
            )
        }
    }

    private companion object {
        const val TAG = "SeekMindBench"
        const val VECTORS = 20_000
        const val WARMUP = 5
        const val RUNS = 15
    }
}
