package fr.corsicajack.seekmind.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.corsicajack.seekmind.core.common.SmLog
import fr.corsicajack.seekmind.core.database.store.SearchHistoryEntry
import fr.corsicajack.seekmind.core.database.store.SearchHistoryStore
import fr.corsicajack.seekmind.core.model.Authorization
import fr.corsicajack.seekmind.core.model.CaptureStatus
import fr.corsicajack.seekmind.core.model.ParsedQuery
import fr.corsicajack.seekmind.core.model.SearchFilters
import fr.corsicajack.seekmind.core.model.SearchHit
import fr.corsicajack.seekmind.core.model.SearchRequest
import fr.corsicajack.seekmind.core.model.SearchResults
import fr.corsicajack.seekmind.core.model.SourceRef
import fr.corsicajack.seekmind.core.model.Stage
import fr.corsicajack.seekmind.core.model.TimeRange
import fr.corsicajack.seekmind.core.model.port.EntitlementGate
import fr.corsicajack.seekmind.core.model.port.SearchEngine
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The search screen (docs/specs/ui-ux.md §3.2, ticket M6-03).
 *
 * - A new search cancels the previous one; the keyword results are shown as they arrive, then the
 *   hybrid results.
 * - The entitlement gate is asked before the search and told the result count after the final emission.
 * - The history records submitted queries only while the setting allows it, and is shown only then.
 * - Removing a filter understood in the query searches again without it: the query becomes the text
 *   without its expressions, and the other understood filters are kept as explicit filters.
 *
 * @param engine null when the database is unavailable; called on [io], like [history] and the other lambdas.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModel(
    private val engine: suspend () -> SearchEngine?,
    private val gate: EntitlementGate,
    private val history: suspend () -> SearchHistoryStore?,
    historyEnabled: Flow<Boolean>,
    private val pendingVectors: suspend () -> Int,
    private val indexedApps: suspend () -> List<String>,
    private val labels: AppLabels,
    private val now: () -> Long,
    private val zone: () -> ZoneId,
    private val io: CoroutineDispatcher,
    /** Captures deleted elsewhere (detail, journal): removed from the results shown. */
    deletions: Flow<Long> = emptyFlow(),
    /** The way back to the source of a result (docs/specs/retour-source.md §3); called off the main thread. */
    private val resolveSource: suspend (SearchHit) -> SourceRef? = { null },
    /** The capture status (docs/specs/capture.md §8): the banner of the screen. */
    captureStatus: Flow<CaptureStatus> = emptyFlow(),
) : ViewModel() {
    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private var search: Job? = null

    /**
     * Ways back resolved for the current search, by capture id: each result is resolved once. Written on
     * [io] as each one is resolved, so that a resolution cancelled by the next results keeps what it did.
     */
    private val sources = ConcurrentHashMap<Long, SourceRef>()
    private var resolving: Job? = null
    private var recordHistory = true

    /** Filters understood in the text of a previous query and kept after another one was removed. */
    private var pinned = SearchFilters()

    init {
        viewModelScope.launch {
            captureStatus.collect { status -> _state.update { it.copy(capture = status) } }
        }
        viewModelScope.launch {
            deletions.collect { id ->
                _state.update { state -> state.copy(results = state.results.filter { it.hit.captureId != id }) }
            }
        }
        viewModelScope.launch {
            historyEnabled.flatMapLatest { enabled ->
                recordHistory = enabled
                if (enabled) {
                    history()?.observeRecent(RECENT) ?: flowOf(emptyList())
                } else {
                    flowOf(emptyList())
                }
            }.collect { entries ->
                _state.update { state -> state.copy(recent = entries.map { it.query }.distinct()) }
            }
        }
        viewModelScope.launch {
            val apps = withContext(io) {
                runCatching { indexedApps().map { AppChoice(it, labels.label(it)) } }.getOrDefault(emptyList())
            }
            _state.update { it.copy(apps = apps.sortedBy { app -> app.label.lowercase() }) }
        }
    }

    fun onQueryChange(query: String) = _state.update { it.copy(query = query) }

    /** « Réessayer » after a failed search: the same query and filters. */
    fun retry() = run()

    /** Runs [query]; a blank query clears the results. */
    fun submit(query: String = _state.value.query) {
        pinned = SearchFilters()
        _state.update { it.copy(query = query) }
        run()
    }

    fun setPeriod(period: Period?) {
        _state.update { it.copy(period = period) }
        if (_state.value.query.isNotBlank()) run()
    }

    fun setApp(packageName: String?) {
        _state.update { it.copy(app = packageName) }
        if (_state.value.query.isNotBlank()) run()
    }

    fun removeUnderstood(filter: UnderstoodFilter) {
        val current = _state.value
        val kept = current.understood - filter
        val range = (kept.firstOrNull { it is UnderstoodFilter.Range } as? UnderstoodFilter.Range)?.range
        val packages = kept.filterIsInstance<UnderstoodFilter.App>().map { it.packageName }.toSet()
        pinned = SearchFilters(range, packages)
        _state.update { it.copy(query = lastParsed?.text ?: it.query) }
        run()
    }

    private var lastParsed: ParsedQuery? = null

    private fun filters(state: SearchUiState): SearchFilters = SearchFilters(
        range = state.period?.range(now(), zone()) ?: pinned.range,
        packages = state.app?.let { setOf(it) } ?: pinned.packages,
    )

    private fun run() {
        search?.cancel()
        resolving?.cancel()
        // Installed applications may have changed since the previous search.
        sources.clear()
        val current = _state.value
        val query = current.query.trim()
        if (query.isEmpty()) {
            _state.update {
                it.copy(
                    phase = SearchPhase.IDLE,
                    results = emptyList(),
                    understood = emptyList(),
                    refining = false,
                    relaxed = null,
                )
            }
            return
        }
        val filters = filters(current)
        val key = QueryKey.of(query, filters)
        search = viewModelScope.launch {
            _state.update {
                it.copy(
                    phase = SearchPhase.SEARCHING,
                    refining = true,
                    results = emptyList(),
                    relaxed = null,
                    notCharged = false,
                    failedAfterResults = false,
                )
            }
            var last: SearchResults? = null
            val authorization: Authorization
            try {
                // Opening the search (providers, native library) and the gate can fail too: same outcome.
                val searchEngine = withContext(io) { engine() }
                if (searchEngine == null) {
                    _state.update { it.copy(phase = SearchPhase.UNAVAILABLE, refining = false) }
                    return@launch
                }
                authorization = gate.authorize(key)
                if (authorization is Authorization.NoCredit) {
                    _state.update { it.copy(phase = SearchPhase.NO_CREDIT, refining = false) }
                    return@launch
                }
                searchEngine.search(SearchRequest(query, filters, LIMIT, now(), zone())).collect { results ->
                    last = results
                    show(results, final = results.stage == Stage.HYBRID || !results.semanticAvailable)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // A failing search is not an unavailable database: never lead to the reset from here.
                SmLog.w(TAG, "search_failed", failure)
                _state.update {
                    if (it.results.isNotEmpty()) {
                        it.copy(phase = SearchPhase.RESULTS, refining = false, failedAfterResults = true)
                    } else {
                        it.copy(phase = SearchPhase.FAILED, refining = false)
                    }
                }
                return@launch
            }
            val final = last ?: return@launch
            _state.update { it.copy(notCharged = final.hits.isEmpty()) }
            // The results are shown: a failure of what follows is only logged.
            try {
                gate.commit(key, final.hits.size)
                val willCharge = (authorization as Authorization.Granted).willCharge && final.hits.isNotEmpty()
                if (recordHistory) {
                    withContext(io) {
                        history()?.add(SearchHistoryEntry(now(), query, key, final.hits.size, willCharge))
                    }
                }
                val pending = withContext(io) { pendingVectors() }
                _state.update { it.copy(pendingVectors = pending) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                SmLog.w(TAG, "after_search_failed", failure)
            }
        }
    }

    private suspend fun show(results: SearchResults, final: Boolean) {
        lastParsed = results.parsed
        val items = withContext(io) {
            results.hits.map { SearchResultItem(it, labels.label(it.packageName), sources[it.captureId]) }
        }
        val understood = buildList {
            val parsed = results.parsed
            val range: TimeRange? = parsed.range
            if (parsed.rangeLabel != null && range != null && _state.value.period == null) {
                add(UnderstoodFilter.Range(parsed.rangeLabel!!, range))
            }
            if (_state.value.app == null) {
                parsed.packages.forEach { add(UnderstoodFilter.App(it, withContext(io) { labels.label(it) })) }
            }
        }
        _state.update {
            it.copy(
                phase = when {
                    items.isNotEmpty() -> SearchPhase.RESULTS
                    final -> SearchPhase.NO_RESULT
                    else -> SearchPhase.SEARCHING
                },
                refining = !final,
                results = items,
                understood = understood,
                relaxed = results.relaxed,
                relaxedLabel = results.parsed.rangeLabel,
                terms = (results.parsed.tokens + results.parsed.codeTokens).distinct(),
                semanticAvailable = results.semanticAvailable,
            )
        }
        val missing = items.filter { it.source == null }.map { it.hit }
        if (missing.isNotEmpty()) {
            resolving?.cancel()
            resolving = viewModelScope.launch { resolveSources(missing) }
        }
    }

    /** Resolves the ways back of [hits] and puts them on the results still shown. */
    private suspend fun resolveSources(hits: List<SearchHit>) {
        var found = 0
        withContext(io) {
            for (hit in hits) {
                try {
                    resolveSource(hit)?.let { ref ->
                        sources[hit.captureId] = ref
                        found++
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    SmLog.w(TAG, "source_resolution_failed", failure)
                }
            }
        }
        if (found == 0) return
        _state.update { state ->
            state.copy(results = state.results.map { it.copy(source = it.source ?: sources[it.hit.captureId]) })
        }
    }

    private companion object {
        const val TAG = "SearchScreen"
        const val LIMIT = 50
        const val RECENT = 5
    }
}
