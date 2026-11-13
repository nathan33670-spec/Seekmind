package fr.corsicajack.seekmind.engine.search.hybrid

import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.database.store.FragmentSpan
import fr.corsicajack.seekmind.core.database.store.LexicalRow
import fr.corsicajack.seekmind.core.database.store.SearchStore
import fr.corsicajack.seekmind.core.model.Capture
import fr.corsicajack.seekmind.core.model.MatchKind
import fr.corsicajack.seekmind.core.model.Relaxation
import fr.corsicajack.seekmind.core.model.SearchFilters
import fr.corsicajack.seekmind.core.model.SearchRequest
import fr.corsicajack.seekmind.core.model.SearchResults
import fr.corsicajack.seekmind.core.model.Stage
import fr.corsicajack.seekmind.core.model.TimeRange
import fr.corsicajack.seekmind.core.model.config.SearchConfig
import fr.corsicajack.seekmind.core.testing.FakeClock
import fr.corsicajack.seekmind.core.testing.Models
import fr.corsicajack.seekmind.engine.search.lexical.LexicalSearcher
import fr.corsicajack.seekmind.engine.search.query.AppExtractor
import fr.corsicajack.seekmind.engine.search.query.QueryParser
import fr.corsicajack.seekmind.engine.search.query.StopWords
import fr.corsicajack.seekmind.engine.search.vector.SemanticHit
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

class HybridSearcherTest {
    private val now = 1_794_441_600_000L
    private val store = FakeSearchStore()
    private val semantic = FakeSemantic()
    private val cache = SearchCache(SearchConfig(), FakeClock())

    private fun searcher(source: SemanticSource = semantic) = HybridSearcher(
        parser = QueryParser(StopWords(listOf("la", "de"))),
        apps = { AppExtractor.NONE },
        lexical = LexicalSearcher(store),
        semantic = source,
        store = store,
        cache = cache,
    )

    private fun request(query: String, filters: SearchFilters = SearchFilters()) =
        SearchRequest(query, filters, limit = 50, now = now, zone = ZoneId.of("Europe/Paris"))

    @Test
    fun keywordResultsComeFirstThenTheFusedResults() = runTest {
        store.add(Models.capture("recette de la tarte aux pommes", id = 1, at = now))
        store.add(Models.capture("dessert aux fruits du verger", id = 2, at = now))
        store.matches = listOf(1L)
        semantic.hits = listOf(SemanticHit(2, 102, 0.9f), SemanticHit(1, 101, 0.85f))
        val emissions = searcher().search(request("tarte")).toList()
        assertThat(emissions.map { it.stage }).containsExactly(Stage.LEXICAL, Stage.HYBRID).inOrder()
        assertThat(emissions[0].hits.map { it.captureId }).containsExactly(1L)
        assertThat(emissions[1].hits.map { it.captureId }).containsExactly(1L, 2L).inOrder()
        assertThat(emissions[1].hits[0].matchedBy).containsExactly(MatchKind.LEXICAL, MatchKind.SEMANTIC)
        assertThat(emissions.all { it.semanticAvailable }).isTrue()
        assertThat(semantic.queries).containsExactly("tarte")
    }

    @Test
    fun cancellingTheFlowStopsBothBranches() = runTest {
        store.add(Models.capture("tarte", id = 1, at = now))
        store.matches = listOf(1L)
        val blocked = BlockingSemantic()
        val job = launch { searcher(blocked).search(request("tarte")).collect {} }
        runCurrent()
        assertThat(blocked.started.isCompleted).isTrue()
        job.cancel()
        runCurrent()
        assertThat(blocked.cancelled).isTrue()
    }

    @Test
    fun withoutAnEngineASingleKeywordEmission() = runTest {
        store.add(Models.capture("tarte", id = 1, at = now))
        store.matches = listOf(1L)
        semantic.configured = false
        val emissions = searcher().search(request("tarte")).toList()
        assertThat(emissions).hasSize(1)
        assertThat(emissions[0].stage).isEqualTo(Stage.LEXICAL)
        assertThat(emissions[0].semanticAvailable).isFalse()
        assertThat(emissions[0].hits.map { it.captureId }).containsExactly(1L)
        assertThat(semantic.queries).isEmpty()
    }

    @Test
    fun withoutAnyVectorTheHybridStageIsKeywordOnly() = runTest {
        store.add(Models.capture("tarte", id = 1, at = now))
        store.matches = listOf(1L)
        semantic.vectors = false
        val emissions = searcher().search(request("tarte")).toList()
        assertThat(emissions.map { it.stage }).containsExactly(Stage.LEXICAL, Stage.HYBRID).inOrder()
        assertThat(emissions[1].semanticAvailable).isFalse()
        assertThat(emissions[1].hits.map { it.captureId }).containsExactly(1L)
        assertThat(semantic.queries).isEmpty() // no query embedding without vectors
    }

    @Test
    fun aFailingSemanticBranchKeepsTheKeywordResults() = runTest {
        store.add(Models.capture("tarte", id = 1, at = now))
        store.matches = listOf(1L)
        semantic.failure = IllegalStateException("model missing")
        val emissions = searcher().search(request("tarte")).toList()
        assertThat(emissions[1].semanticAvailable).isFalse()
        assertThat(emissions[1].hits.map { it.captureId }).containsExactly(1L)
    }

    @Test
    fun filtersAreRelaxedTimeFirstThenAll() = runTest {
        val range = TimeRange(now - 1_000, now)
        store.add(Models.capture("tarte ancienne", id = 1, at = now - 10 * DAY, packageName = "com.example.notes"))
        store.add(Models.capture("tarte ailleurs", id = 2, at = now - 10 * DAY, packageName = "com.example.mail"))
        store.matches = listOf(1L, 2L)
        semantic.vectors = false

        val time = searcher().search(request("tarte", SearchFilters(range, setOf("com.example.notes")))).toList().last()
        assertThat(time.relaxed).isEqualTo(Relaxation.TIME)
        assertThat(time.hits.map { it.captureId }).containsExactly(1L)

        val all = searcher().search(request("tarte", SearchFilters(range, setOf("com.example.chat")))).toList().last()
        assertThat(all.relaxed).isEqualTo(Relaxation.ALL)
        assertThat(all.hits.map { it.captureId }).containsExactly(1L, 2L)

        val none = searcher().search(request("tarte", SearchFilters(range))).toList()
        assertThat(none.first().relaxed).isNull() // the keyword stage is never relaxed
        assertThat(none.last().relaxed).isEqualTo(Relaxation.TIME)
    }

    @Test
    fun relaxationAlsoAppliesToTheSemanticBranch() = runTest {
        val range = TimeRange(now - 1_000, now)
        store.add(Models.capture("dessert", id = 3, at = now - 10 * DAY))
        semantic.hits = listOf(SemanticHit(3, 103, 0.9f))
        semantic.respectsRange = true
        val last = searcher().search(request("gâteau", SearchFilters(range))).toList().last()
        assertThat(last.relaxed).isEqualTo(Relaxation.TIME)
        assertThat(last.hits.map { it.captureId }).containsExactly(3L)
        assertThat(semantic.queries).containsExactly("gâteau") // the query is embedded once
    }

    @Test
    fun noResultWithoutFilterIsNotRelaxed() = runTest {
        val emissions = searcher().search(request("introuvable")).toList()
        assertThat(emissions.last().hits).isEmpty()
        assertThat(emissions.last().relaxed).isNull()
    }

    @Test
    fun aRepeatedQueryComesFromTheCacheInOneEmission() = runTest {
        store.add(Models.capture("tarte", id = 1, at = now))
        store.matches = listOf(1L)
        searcher().search(request("tarte")).toList()
        val calls = store.lexicalCalls
        val again = searcher().search(request("tarte")).toList()
        assertThat(again).hasSize(1)
        assertThat(again[0].stage).isEqualTo(Stage.HYBRID)
        assertThat(store.lexicalCalls).isEqualTo(calls)
        assertThat(semantic.queries).hasSize(1)
    }

    @Test
    fun aQueryMadeOfAnExpressionOnlyEmbedsNothing() = runTest {
        val emissions = searcher().search(request("   ")).toList()
        assertThat(emissions).hasSize(1)
        assertThat(emissions[0].semanticAvailable).isFalse()
        assertThat(semantic.queries).isEmpty()
    }

    @Test
    fun theFirstEmissionDoesNotWaitForTheSemanticBranch() = runTest {
        store.add(Models.capture("tarte", id = 1, at = now))
        store.matches = listOf(1L)
        val blocked = BlockingSemantic()
        val first: SearchResults = searcher(blocked).search(request("tarte")).first()
        assertThat(first.stage).isEqualTo(Stage.LEXICAL)
        assertThat(first.hits.map { it.captureId }).containsExactly(1L)
    }

    private class FakeSearchStore : SearchStore {
        val captures = LinkedHashMap<Long, Capture>()
        var matches: List<Long> = emptyList()
        var lexicalCalls = 0

        fun add(capture: Capture) {
            captures[capture.id] = capture
        }

        override suspend fun lexical(match: String, filters: SearchFilters, limit: Int): List<LexicalRow> {
            lexicalCalls++
            return matches.mapNotNull { captures[it] }.filter { it.passes(filters) }
                .map { LexicalRow(it.id, -1.0, it.text) }.take(limit)
        }

        override suspend fun captures(ids: List<Long>): List<Capture> = ids.mapNotNull { captures[it] }

        override suspend fun fragments(embeddingIds: List<Long>): List<FragmentSpan> = embeddingIds.mapNotNull { id ->
            captures[id - 100]?.let { FragmentSpan(id, it.id, 0, it.text.length) }
        }
    }

    private inner class FakeSemantic : SemanticSource {
        var configured = true
        var vectors = true
        var failure: Exception? = null
        var hits: List<SemanticHit> = emptyList()
        var respectsRange = false
        val queries = mutableListOf<String>()

        override fun isConfigured() = configured

        override suspend fun hasVectors() = vectors

        override suspend fun queryVector(text: String): FloatArray {
            failure?.let { throw it }
            queries += text
            return FloatArray(384)
        }

        override suspend fun search(query: FloatArray, filters: SearchFilters): List<SemanticHit> =
            if (respectsRange) hits.filter { store.captures[it.captureId]?.passes(filters) == true } else hits
    }

    private class BlockingSemantic : SemanticSource {
        val started = CompletableDeferred<Unit>()
        var cancelled = false

        override fun isConfigured() = true

        override suspend fun hasVectors() = true

        override suspend fun queryVector(text: String): FloatArray {
            started.complete(Unit)
            try {
                CompletableDeferred<Unit>().await()
            } finally {
                cancelled = true
            }
            error("unreachable")
        }

        override suspend fun search(query: FloatArray, filters: SearchFilters): List<SemanticHit> = emptyList()
    }

    private companion object {
        const val DAY = 86_400_000L

        fun Capture.passes(filters: SearchFilters): Boolean {
            val range = filters.range
            if (range != null && (lastSeenAt < range.from || firstSeenAt > range.to)) return false
            return filters.packages.isEmpty() || packageName in filters.packages
        }
    }
}
