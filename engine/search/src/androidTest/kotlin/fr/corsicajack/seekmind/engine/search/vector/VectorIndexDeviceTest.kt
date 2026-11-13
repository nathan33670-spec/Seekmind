package fr.corsicajack.seekmind.engine.search.vector

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
import fr.corsicajack.seekmind.core.testing.TemporaryDatabase
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
}
