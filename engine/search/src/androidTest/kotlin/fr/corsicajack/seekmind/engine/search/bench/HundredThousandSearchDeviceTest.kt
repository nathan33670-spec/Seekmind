package fr.corsicajack.seekmind.engine.search.bench

import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.common.SystemClockJvm
import fr.corsicajack.seekmind.core.database.DatabaseHandle
import fr.corsicajack.seekmind.core.model.SearchFilters
import fr.corsicajack.seekmind.core.model.SearchRequest
import fr.corsicajack.seekmind.core.model.Stage
import fr.corsicajack.seekmind.core.model.config.EmbeddingConfig
import fr.corsicajack.seekmind.core.model.config.SearchConfig
import fr.corsicajack.seekmind.core.model.port.EmbeddingEngine
import fr.corsicajack.seekmind.core.model.port.EmbeddingEngineProvider
import fr.corsicajack.seekmind.core.testing.FakeEmbeddingEngine
import fr.corsicajack.seekmind.core.testing.Models
import fr.corsicajack.seekmind.core.testing.SyntheticCorpus
import fr.corsicajack.seekmind.core.testing.SyntheticDatabase
import fr.corsicajack.seekmind.core.testing.TemporaryDatabase
import fr.corsicajack.seekmind.engine.embedding.codec.VectorCodec
import fr.corsicajack.seekmind.engine.search.hybrid.HybridSearcher
import fr.corsicajack.seekmind.engine.search.hybrid.SearchCache
import fr.corsicajack.seekmind.engine.search.hybrid.VectorSemanticSource
import fr.corsicajack.seekmind.engine.search.lexical.LexicalSearcher
import fr.corsicajack.seekmind.engine.search.query.AppExtractor
import fr.corsicajack.seekmind.engine.search.query.QueryParser
import fr.corsicajack.seekmind.engine.search.query.StopWords
import fr.corsicajack.seekmind.engine.search.query.TemporalParser
import fr.corsicajack.seekmind.engine.search.vector.VectorIndex
import java.time.ZoneId
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ENF-PERF-03 and 04 on 100,000 synthetic captures and 100,000 vectors (ticket M5-08,
 * docs/06-qualite-tests-ci.md §6): the keyword search, the loading of the vector index, the semantic
 * search (preselection and rescore together, as [VectorIndex.search] runs them), and the whole hybrid
 * chain, first emission (keywords) and last (fused). The two branches run in parallel: what the last
 * emission adds to the slower of them is the fusion and its database reads. [FakeEmbeddingEngine] embeds the query at no cost: the hybrid figures are those of
 * ENF-PERF-04 "query embedding aside"; the engine benches give the embedding itself.
 *
 * Measurement only, as ENF-PERF-01 and 05 on the shared emulators of the CI (decision of the project
 * owner, 13 November 2026): the figures and whether the targets are met are logged under
 * `SeekMindBench`, the test fails only when a search finds nothing.
 */
@RunWith(AndroidJUnit4::class)
class HundredThousandSearchDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val database = TemporaryDatabase(context, "bench_100k")
    private val codec = VectorCodec(EmbeddingConfig().int8Scale)
    private val engine = FakeEmbeddingEngine()
    private val corpus = SyntheticCorpus()

    // No cache, so that every query is computed; no floor, so that the semantic branch returns its whole
    // list, whatever the similarity of the pseudo-random vectors: the costliest case for the fusion.
    private val config = SearchConfig(semanticFloor = -1f, cacheSize = 0)

    @After
    fun tearDown() = database.delete()

    @Test
    fun measuresTheSearchOn100000Captures() = runBlocking {
        database.delete()
        val handle = database.open()
        val generateMs = SyntheticDatabase(handle, corpus).populate(COUNT, vectors = true)
        log("m5_08_generate_${COUNT}_with_vectors_ms", generateMs.toDouble())

        val index = VectorIndex(handle.vectors, config, codec, Dispatchers.Default)
        val loadMs = timed { index.refresh() }
        assertThat(index.size()).isEqualTo(COUNT)
        log("m5_08_index_load_ms", loadMs)

        val random = Random(17)
        val queries = List(WARMUP + RUNS) { "${corpus.queryWord(random)} ${corpus.queryWord(random)}" }
        val parser = QueryParser(StopWords.load(context.resources), TemporalParser())
        val lexical = LexicalSearcher(handle.search)

        report("lexical", queries) { query ->
            val hits = lexical.search(
                parser.parse(request(query), AppExtractor.NONE),
                SearchFilters(),
                config.lexicalLimit,
            )
            assertThat(hits).isNotEmpty()
        }
        report("semantic", queries) { query ->
            assertThat(index.search(engine.embedQuery(query), SearchFilters())).isNotEmpty()
        }

        val searcher = searcher(handle, index)
        val first = mutableListOf<Double>()
        val total = mutableListOf<Double>()
        queries.forEachIndexed { i, query ->
            val start = SystemClock.elapsedRealtimeNanos()
            var firstAt = 0L
            var hybridHits = 0
            searcher.search(request(query)).collect { results ->
                if (results.stage == Stage.LEXICAL) firstAt = SystemClock.elapsedRealtimeNanos()
                if (results.stage == Stage.HYBRID) hybridHits = results.hits.size
            }
            val end = SystemClock.elapsedRealtimeNanos()
            assertThat(hybridHits).isGreaterThan(0)
            if (i >= WARMUP) {
                first += (firstAt - start) / 1e6
                total += (end - start) / 1e6
            }
        }
        logPercentiles("hybrid_first_emission", first, targetP95Ms = PERF_03_MS)
        logPercentiles("hybrid_total_without_query_embedding", total, targetP95Ms = PERF_04_WITHOUT_EMBEDDING_MS)
    }

    private fun searcher(handle: DatabaseHandle, index: VectorIndex): HybridSearcher {
        val provider = object : EmbeddingEngineProvider {
            override suspend fun <T> use(block: suspend (EmbeddingEngine) -> T): T = block(engine)
        }
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

    private fun request(query: String) = SearchRequest(query, now = Models.AT, zone = ZoneId.of("Europe/Paris"))

    private suspend fun report(name: String, queries: List<String>, block: suspend (String) -> Unit) {
        val times = queries.mapIndexedNotNull { i, query ->
            val ms = timed { block(query) }
            if (i < WARMUP) null else ms
        }
        logPercentiles(name, times, targetP95Ms = null)
    }

    private suspend fun timed(block: suspend () -> Unit): Double {
        val start = SystemClock.elapsedRealtimeNanos()
        block()
        return (SystemClock.elapsedRealtimeNanos() - start) / 1e6
    }

    private fun logPercentiles(name: String, times: List<Double>, targetP95Ms: Double?) {
        val sorted = times.sorted()
        val p50 = sorted[sorted.size / 2]
        val p95 = sorted[(sorted.size * 95 + 99) / 100 - 1]
        log("m5_08_${name}_ms_p50", p50)
        log("m5_08_${name}_ms_p95", p95)
        if (targetP95Ms != null) log("m5_08_${name}_target_p95_met", if (p95 <= targetP95Ms) 1.0 else 0.0)
    }

    private fun log(key: String, value: Double) = Log.i(TAG, "api=${Build.VERSION.SDK_INT} $key=$value")

    private companion object {
        const val TAG = "SeekMindBench"
        const val COUNT = 100_000
        const val WARMUP = 10
        const val RUNS = 50

        // ENF-PERF-03: first display of the keyword results; ENF-PERF-04: hybrid search, query
        // embedding aside (docs/02-cahier-des-charges.md §5).
        const val PERF_03_MS = 100.0
        const val PERF_04_WITHOUT_EMBEDDING_MS = 30.0
    }
}
