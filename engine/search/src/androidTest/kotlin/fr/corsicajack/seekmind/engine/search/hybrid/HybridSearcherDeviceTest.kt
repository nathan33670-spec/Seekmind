package fr.corsicajack.seekmind.engine.search.hybrid

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.common.SystemClockJvm
import fr.corsicajack.seekmind.core.database.DatabaseHandle
import fr.corsicajack.seekmind.core.database.store.NewChunk
import fr.corsicajack.seekmind.core.database.store.NewEmbedding
import fr.corsicajack.seekmind.core.model.MatchKind
import fr.corsicajack.seekmind.core.model.Relaxation
import fr.corsicajack.seekmind.core.model.SearchFilters
import fr.corsicajack.seekmind.core.model.SearchRequest
import fr.corsicajack.seekmind.core.model.Stage
import fr.corsicajack.seekmind.core.model.TimeRange
import fr.corsicajack.seekmind.core.model.config.EmbeddingConfig
import fr.corsicajack.seekmind.core.model.config.SearchConfig
import fr.corsicajack.seekmind.core.model.port.EmbeddingEngine
import fr.corsicajack.seekmind.core.model.port.EmbeddingEngineProvider
import fr.corsicajack.seekmind.core.testing.FakeEmbeddingEngine
import fr.corsicajack.seekmind.core.testing.Models
import fr.corsicajack.seekmind.core.testing.TemporaryDatabase
import fr.corsicajack.seekmind.engine.embedding.codec.VectorCodec
import fr.corsicajack.seekmind.engine.search.lexical.LexicalSearcher
import fr.corsicajack.seekmind.engine.search.query.AppExtractor
import fr.corsicajack.seekmind.engine.search.query.QueryParser
import fr.corsicajack.seekmind.engine.search.query.StopWords
import fr.corsicajack.seekmind.engine.search.query.TemporalParser
import fr.corsicajack.seekmind.engine.search.vector.VectorIndex
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The whole hybrid search on the encrypted database, with [FakeEmbeddingEngine] (docs/specs/recherche.md §12). */
@RunWith(AndroidJUnit4::class)
class HybridSearcherDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val database = TemporaryDatabase(context, "hybrid_search")
    private val codec = VectorCodec(EmbeddingConfig().int8Scale)
    private val engine = FakeEmbeddingEngine()
    private lateinit var handle: DatabaseHandle

    // The fake engine's vectors are unrelated to the meaning: no floor, every vector is a candidate.
    private val config = SearchConfig(semanticFloor = -1f)

    @Before
    fun setUp() {
        handle = database.open()
    }

    @After
    fun tearDown() = database.delete()

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

    private fun searcher(): HybridSearcher {
        val provider = object : EmbeddingEngineProvider {
            override suspend fun <T> use(block: suspend (EmbeddingEngine) -> T): T = block(engine)
        }
        val index = VectorIndex(handle.vectors, config, codec, Dispatchers.Default)
        return HybridSearcher(
            parser = QueryParser(StopWords.load(context.resources), TemporalParser()),
            apps = { AppExtractor.NONE },
            lexical = LexicalSearcher(handle.search),
            semantic = VectorSemanticSource(provider, index),
            store = handle.search,
            cache = SearchCache(config, SystemClockJvm),
            generation = { handle.vectors.indexGeneration() },
            config = config,
        )
    }

    private fun request(query: String, filters: SearchFilters = SearchFilters()) =
        SearchRequest(query, filters, limit = 50, now = NOW, zone = ZoneId.of("Europe/Paris"))

    @Test
    fun keywordsThenFusedResultsWithSemanticExtracts() = runBlocking<Unit> {
        val tart = capture("Recette de la tarte aux pommes de grand-mère", at = NOW - DAY)
        val other = capture("Horaires d'ouverture de la piscine municipale le dimanche", at = NOW - 2 * DAY)
        val emissions = searcher().search(request("tarte")).toList()

        assertThat(emissions.map { it.stage }).containsExactly(Stage.LEXICAL, Stage.HYBRID).inOrder()
        assertThat(emissions[0].hits.map { it.captureId }).containsExactly(tart)
        val hybrid = emissions[1]
        assertThat(hybrid.semanticAvailable).isTrue()
        assertThat(hybrid.hits.first().captureId).isEqualTo(tart) // first in both lists or by keywords
        assertThat(hybrid.hits.first().matchedBy).contains(MatchKind.LEXICAL)
        assertThat(hybrid.hits.first().snippet.highlights).isNotEmpty()
        val semanticOnly = hybrid.hits.single { it.captureId == other }
        assertThat(semanticOnly.matchedBy).containsExactly(MatchKind.SEMANTIC)
        assertThat(semanticOnly.snippet.text).startsWith("Horaires d'ouverture")
    }

    @Test
    fun anEmptyTimeRangeIsRelaxed() = runBlocking<Unit> {
        val tart = capture("Recette de la tarte aux pommes", at = NOW - 10 * DAY)
        val results = searcher().search(
            request("tarte", SearchFilters(range = TimeRange(NOW - DAY, NOW))),
        ).toList().last()
        assertThat(results.relaxed).isEqualTo(Relaxation.TIME)
        assertThat(results.hits.map { it.captureId }).contains(tart)
    }

    @Test
    fun aDeletedCaptureLeavesBothBranches() = runBlocking<Unit> {
        val tart = capture("Recette de la tarte aux pommes", at = NOW - DAY)
        val kept = capture("Tarte au citron meringuée", at = NOW - DAY)
        val searcher = searcher()
        searcher.search(request("tarte")).toList()
        handle.captures.delete(tart)
        // The same query: the deletion made the cached result stale.
        val after = searcher.search(request("tarte")).toList().last()
        assertThat(after.hits.map { it.captureId }).containsExactly(kept)
    }

    private companion object {
        const val NOW = 1_794_441_600_000L
        const val DAY = 86_400_000L
    }
}
