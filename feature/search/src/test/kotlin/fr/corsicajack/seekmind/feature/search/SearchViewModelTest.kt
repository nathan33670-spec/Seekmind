package fr.corsicajack.seekmind.feature.search

import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.database.store.SearchHistoryEntry
import fr.corsicajack.seekmind.core.database.store.SearchHistoryStore
import fr.corsicajack.seekmind.core.model.Authorization
import fr.corsicajack.seekmind.core.model.Entitlement
import fr.corsicajack.seekmind.core.model.MatchKind
import fr.corsicajack.seekmind.core.model.ParsedQuery
import fr.corsicajack.seekmind.core.model.QueryProfile
import fr.corsicajack.seekmind.core.model.Relaxation
import fr.corsicajack.seekmind.core.model.SearchHit
import fr.corsicajack.seekmind.core.model.SearchRequest
import fr.corsicajack.seekmind.core.model.SearchResults
import fr.corsicajack.seekmind.core.model.Snippet
import fr.corsicajack.seekmind.core.model.Stage
import fr.corsicajack.seekmind.core.model.TimeRange
import fr.corsicajack.seekmind.core.model.port.EntitlementGate
import fr.corsicajack.seekmind.core.model.port.SearchEngine
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val engine = FakeEngine()
    private val gate = FakeGate()
    private val history = FakeHistory()
    private val historyEnabled = MutableStateFlow(true)
    private var available = true
    private var pending = 0

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel() = SearchViewModel(
        engine = { if (available) engine else null },
        gate = gate,
        history = { history },
        historyEnabled = historyEnabled,
        pendingVectors = { pending },
        indexedApps = { listOf("com.example.notes", "com.example.chat") },
        labels = { it.substringAfterLast('.').replaceFirstChar(Char::uppercase) },
        now = { NOW },
        zone = { ZoneId.of("Europe/Paris") },
        io = StandardTestDispatcher(testScheduler),
    )

    @Test
    fun idleShowsTheRecentSearchesAndTheIndexedApps() = runTest(dispatcher) {
        history.add(SearchHistoryEntry(NOW - 1, "tarte", "k", 1, false))
        val vm = viewModel()
        advanceUntilIdle()
        assertThat(vm.state.value.phase).isEqualTo(SearchPhase.IDLE)
        assertThat(vm.state.value.recent).containsExactly("tarte")
        assertThat(vm.state.value.apps.map { it.label }).containsExactly("Chat", "Notes").inOrder()
    }

    @Test
    fun keywordResultsThenHybridResults() = runTest(dispatcher) {
        val release = CompletableDeferred<Unit>()
        engine.respond = { request ->
            flow {
                emit(results(Stage.LEXICAL, request, hit(1)))
                release.await()
                emit(results(Stage.HYBRID, request, hit(1), hit(2)))
            }
        }
        val vm = viewModel()
        vm.submit("tarte")
        runCurrent()
        assertThat(vm.state.value.phase).isEqualTo(SearchPhase.RESULTS)
        assertThat(vm.state.value.refining).isTrue()
        assertThat(vm.state.value.results.map { it.hit.captureId }).containsExactly(1L)
        release.complete(Unit)
        advanceUntilIdle()
        assertThat(vm.state.value.refining).isFalse()
        assertThat(vm.state.value.results.map { it.hit.captureId }).containsExactly(1L, 2L).inOrder()
        assertThat(
            gate.commits,
        ).containsExactly(QueryKey.of("tarte", fr.corsicajack.seekmind.core.model.SearchFilters()) to 2)
    }

    @Test
    fun searchingUntilTheFirstEmission() = runTest(dispatcher) {
        val first = CompletableDeferred<Unit>()
        engine.respond = { request ->
            flow {
                first.await()
                emit(results(Stage.HYBRID, request, hit(1)))
            }
        }
        val vm = viewModel()
        vm.submit("tarte")
        runCurrent()
        assertThat(vm.state.value.phase).isEqualTo(SearchPhase.SEARCHING)
        first.complete(Unit)
        advanceUntilIdle()
        assertThat(vm.state.value.phase).isEqualTo(SearchPhase.RESULTS)
    }

    @Test
    fun aNewQueryCancelsThePreviousOne() = runTest(dispatcher) {
        val never = CompletableDeferred<Unit>()
        var cancelled = false
        engine.respond = { request ->
            if (request.rawQuery == "tarte") {
                flow {
                    emit(results(Stage.LEXICAL, request, hit(1)))
                    try {
                        never.await()
                    } finally {
                        cancelled = true
                    }
                }
            } else {
                flow { emit(results(Stage.HYBRID, request, hit(9))) }
            }
        }
        val vm = viewModel()
        vm.submit("tarte")
        runCurrent()
        vm.submit("piscine")
        advanceUntilIdle()
        assertThat(cancelled).isTrue()
        assertThat(vm.state.value.results.map { it.hit.captureId }).containsExactly(9L)
        assertThat(
            gate.commits.map {
                it.first
            },
        ).containsExactly(QueryKey.of("piscine", fr.corsicajack.seekmind.core.model.SearchFilters()))
    }

    @Test
    fun relaxedResults() = runTest(dispatcher) {
        engine.respond = { request ->
            flow { emit(results(Stage.HYBRID, request, hit(1), relaxed = Relaxation.TIME, rangeLabel = "mardi soir")) }
        }
        val vm = viewModel()
        vm.submit("tarte mardi soir")
        advanceUntilIdle()
        assertThat(vm.state.value.relaxed).isEqualTo(Relaxation.TIME)
        assertThat(vm.state.value.relaxedLabel).isEqualTo("mardi soir")
    }

    @Test
    fun noResultIsNotCharged() = runTest(dispatcher) {
        gate.willCharge = true
        engine.respond = { request -> flow { emit(results(Stage.HYBRID, request)) } }
        val vm = viewModel()
        vm.submit("introuvable")
        advanceUntilIdle()
        assertThat(vm.state.value.phase).isEqualTo(SearchPhase.NO_RESULT)
        assertThat(vm.state.value.notCharged).isTrue()
        assertThat(gate.commits.single().second).isEqualTo(0)
        assertThat(history.entries.single().charged).isFalse()
    }

    @Test
    fun incompleteSemanticIndex() = runTest(dispatcher) {
        pending = 120
        engine.respond = { request -> flow { emit(results(Stage.HYBRID, request, hit(1))) } }
        val vm = viewModel()
        vm.submit("tarte")
        advanceUntilIdle()
        assertThat(vm.state.value.pendingVectors).isEqualTo(120)
    }

    @Test
    fun keywordOnlyWhenTheSemanticSearchIsUnavailable() = runTest(dispatcher) {
        engine.respond =
            { request -> flow { emit(results(Stage.LEXICAL, request, hit(1), semanticAvailable = false)) } }
        val vm = viewModel()
        vm.submit("tarte")
        advanceUntilIdle()
        assertThat(vm.state.value.refining).isFalse() // a single emission is final
        assertThat(vm.state.value.semanticAvailable).isFalse()
        assertThat(gate.commits).hasSize(1)
    }

    @Test
    fun noCredit() = runTest(dispatcher) {
        gate.authorization = Authorization.NoCredit
        val vm = viewModel()
        vm.submit("tarte")
        advanceUntilIdle()
        assertThat(vm.state.value.phase).isEqualTo(SearchPhase.NO_CREDIT)
        assertThat(engine.requests).isEmpty()
    }

    @Test
    fun databaseUnavailable() = runTest(dispatcher) {
        available = false
        val vm = viewModel()
        vm.submit("tarte")
        advanceUntilIdle()
        assertThat(vm.state.value.phase).isEqualTo(SearchPhase.UNAVAILABLE)
    }

    @Test
    fun aFailingSearchNeverOffersTheReset() = runTest(dispatcher) {
        engine.respond = { flow { throw IllegalStateException("transient") } }
        val vm = viewModel()
        vm.submit("tarte")
        advanceUntilIdle()
        assertThat(vm.state.value.phase).isEqualTo(SearchPhase.FAILED)

        engine.respond = { request -> flow { emit(results(Stage.HYBRID, request, hit(1))) } }
        vm.retry()
        advanceUntilIdle()
        assertThat(vm.state.value.phase).isEqualTo(SearchPhase.RESULTS)
        assertThat(engine.requests.last().rawQuery).isEqualTo("tarte")
    }

    @Test
    fun aFailureAfterTheKeywordResultsKeepsThem() = runTest(dispatcher) {
        engine.respond = { request ->
            flow {
                emit(results(Stage.LEXICAL, request, hit(1)))
                throw IllegalStateException("transient")
            }
        }
        val vm = viewModel()
        vm.submit("tarte")
        advanceUntilIdle()
        assertThat(vm.state.value.phase).isEqualTo(SearchPhase.RESULTS)
        assertThat(vm.state.value.results.map { it.hit.captureId }).containsExactly(1L)
        assertThat(vm.state.value.failedAfterResults).isTrue()
        assertThat(vm.state.value.refining).isFalse()
    }

    @Test
    fun aBlankQueryGoesBackToIdle() = runTest(dispatcher) {
        engine.respond = { request -> flow { emit(results(Stage.HYBRID, request, hit(1))) } }
        val vm = viewModel()
        vm.submit("tarte")
        advanceUntilIdle()
        vm.submit("  ")
        advanceUntilIdle()
        assertThat(vm.state.value.phase).isEqualTo(SearchPhase.IDLE)
        assertThat(vm.state.value.results).isEmpty()
    }

    @Test
    fun chipsSetTheFiltersAndRemovingOneSearchesWithoutIt() = runTest(dispatcher) {
        engine.respond = { request -> flow { emit(results(Stage.HYBRID, request, hit(1))) } }
        val vm = viewModel()
        vm.submit("tarte")
        advanceUntilIdle()
        vm.setApp("com.example.notes")
        advanceUntilIdle()
        assertThat(engine.requests.last().filters.packages).containsExactly("com.example.notes")
        vm.setPeriod(Period.TODAY)
        advanceUntilIdle()
        assertThat(engine.requests.last().filters.range).isEqualTo(Period.TODAY.range(NOW, ZoneId.of("Europe/Paris")))
        vm.setApp(null)
        advanceUntilIdle()
        assertThat(engine.requests.last().filters.packages).isEmpty()
        assertThat(engine.requests.last().filters.range).isNotNull()
    }

    @Test
    fun removingAnUnderstoodFilterSearchesAgainWithoutIt() = runTest(dispatcher) {
        val range = TimeRange(NOW - 3_600_000, NOW)
        engine.respond = { request ->
            val fromText = request.rawQuery.contains("hier") && request.rawQuery.contains("instagram")
            flow {
                emit(
                    results(
                        Stage.HYBRID,
                        request,
                        hit(1),
                        rangeLabel = if (fromText) "hier" else null,
                        range = if (fromText) range else request.filters.range,
                        packages = if (fromText) setOf("com.instagram.android") else request.filters.packages,
                        text = "tarte",
                    ),
                )
            }
        }
        val vm = viewModel()
        vm.submit("tarte hier sur instagram")
        advanceUntilIdle()
        assertThat(vm.state.value.understood.map { it.key }).containsExactly("range", "app:com.instagram.android")
        vm.removeUnderstood(vm.state.value.understood.first { it is UnderstoodFilter.Range })
        advanceUntilIdle()
        val last = engine.requests.last()
        assertThat(last.rawQuery).isEqualTo("tarte")
        assertThat(last.filters.range).isNull()
        assertThat(last.filters.packages).containsExactly("com.instagram.android")
        assertThat(vm.state.value.query).isEqualTo("tarte")
    }

    @Test
    fun theHistoryFollowsTheSetting() = runTest(dispatcher) {
        engine.respond = { request -> flow { emit(results(Stage.HYBRID, request, hit(1))) } }
        val vm = viewModel()
        vm.submit("tarte")
        advanceUntilIdle()
        assertThat(history.entries.map { it.query }).containsExactly("tarte")
        assertThat(vm.state.value.recent).containsExactly("tarte")

        historyEnabled.value = false
        advanceUntilIdle()
        assertThat(vm.state.value.recent).isEmpty()
        vm.submit("piscine")
        advanceUntilIdle()
        assertThat(history.entries.map { it.query }).containsExactly("tarte")
    }

    private class FakeEngine : SearchEngine {
        val requests = mutableListOf<SearchRequest>()
        var respond: (
            SearchRequest,
        ) -> Flow<SearchResults> = { request -> flow { emit(results(Stage.HYBRID, request)) } }

        override fun search(request: SearchRequest): Flow<SearchResults> {
            requests += request
            return respond(request)
        }
    }

    private class FakeGate : EntitlementGate {
        override val state: StateFlow<Entitlement> = MutableStateFlow(Entitlement.Free(5))
        var authorization: Authorization = Authorization.Granted(willCharge = false)
        var willCharge = false
        val commits = mutableListOf<Pair<String, Int>>()

        override suspend fun authorize(queryKey: String): Authorization =
            (authorization as? Authorization.Granted)?.copy(willCharge = willCharge) ?: authorization

        override suspend fun commit(queryKey: String, resultCount: Int) {
            commits += queryKey to resultCount
        }
    }

    private class FakeHistory : SearchHistoryStore {
        private val flow = MutableStateFlow<List<SearchHistoryEntry>>(emptyList())
        val entries: List<SearchHistoryEntry> get() = flow.value

        override suspend fun add(entry: SearchHistoryEntry) {
            flow.value = listOf(entry) + flow.value
        }

        override fun observeRecent(limit: Int): Flow<List<SearchHistoryEntry>> = flow.map { it.take(limit) }

        override suspend fun clear() {
            flow.value = emptyList()
        }

        override suspend fun deleteOlderThan(before: Long): Int = 0
    }

    private companion object {
        const val NOW = 1_794_441_600_000L

        fun hit(id: Long) = SearchHit(
            captureId = id,
            score = 1.0 / id,
            packageName = "com.example.notes",
            windowTitle = null,
            url = null,
            firstSeenAt = NOW,
            lastSeenAt = NOW,
            snippet = Snippet("extrait $id", emptyList()),
            textSource = 1,
            matchedBy = setOf(MatchKind.LEXICAL),
            similarCount = 0,
        )

        fun results(
            stage: Stage,
            request: SearchRequest,
            vararg hits: SearchHit,
            relaxed: Relaxation? = null,
            rangeLabel: String? = null,
            semanticAvailable: Boolean = true,
            range: TimeRange? = request.filters.range,
            packages: Set<String> = request.filters.packages,
            text: String = request.rawQuery,
        ) = SearchResults(
            stage = stage,
            parsed = ParsedQuery(text, text.split(' '), emptyList(), range, rangeLabel, packages, QueryProfile.DEFAULT),
            hits = hits.toList(),
            relaxed = relaxed,
            semanticAvailable = semanticAvailable,
        )
    }
}
