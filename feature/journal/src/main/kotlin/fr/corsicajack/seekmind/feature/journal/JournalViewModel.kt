package fr.corsicajack.seekmind.feature.journal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.corsicajack.seekmind.core.database.store.CaptureStore
import fr.corsicajack.seekmind.core.database.store.JournalCursor
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The journal (docs/specs/ui-ux.md §3.4, ticket M6-05): pages of [PAGE_SIZE] lines loaded on demand
 * from a cursor, filter by application, deletion of a line with « Annuler », deletion of a day.
 *
 * Only the pages reached by the scroll are in memory. A deleted line is hidden first; it is deleted
 * from the database when the undo is dismissed, when another line is deleted, or when the screen
 * goes away, on [commitScope], which outlives the screen. The journal never consumes credits.
 *
 * @param store null when the database is unavailable; called on [io].
 */
class JournalViewModel(
    private val store: suspend () -> CaptureStore?,
    private val labels: AppLabels,
    private val zone: () -> ZoneId,
    private val io: CoroutineDispatcher,
    private val commitScope: CoroutineScope,
    /** Captures deleted elsewhere (the detail screen): their line goes. */
    deletions: Flow<Long> = emptyFlow(),
    /** Told after a line is deleted here, so that the other screens drop it too. */
    private val onDeleted: suspend (Long) -> Unit = {},
) : ViewModel() {
    private val _state = MutableStateFlow(JournalUiState())
    val state: StateFlow<JournalUiState> = _state.asStateFlow()

    /** Every line loaded so far, most recent first, hidden one included. */
    private var loaded: List<JournalEntry> = emptyList()
    private var cursor: JournalCursor? = null

    /** Incremented by every reload: a page requested before is dropped. */
    private var generation = 0
    private var requesting = false

    init {
        reload()
        viewModelScope.launch {
            deletions.collect { id ->
                loaded = loaded.filter { it.captureId != id }
                publish { it }
            }
        }
    }

    fun loadMore() {
        val current = _state.value
        if (requesting || current.endReached || current.unavailable) return
        requesting = true
        val requested = generation
        val filter = current.filter
        val after = cursor
        viewModelScope.launch {
            val page = withContext(io) {
                store()?.page(after, filter, PAGE_SIZE)?.map {
                    JournalEntry(it.id, it.lastSeenAt, it.packageName, labels.label(it.packageName), it.headline())
                }
            }
            if (requested != generation) return@launch
            requesting = false
            if (page == null) {
                _state.update { it.copy(loading = false, unavailable = true) }
                return@launch
            }
            loaded = loaded + page
            cursor = page.lastOrNull()?.let { JournalCursor(it.lastSeenAt, it.captureId) } ?: cursor
            publish { it.copy(loading = false, endReached = page.size < PAGE_SIZE) }
        }
    }

    fun setFilter(packageName: String?) {
        if (packageName == _state.value.filter) return
        commitPending()
        _state.update { it.copy(filter = packageName) }
        reload()
    }

    /** Hides the line; [undoDelete] brings it back, [commitPending] deletes it. */
    fun requestDelete(captureId: Long) {
        commitPending()
        publish { it.copy(pendingDeletion = captureId) }
    }

    fun undoDelete() = publish { it.copy(pendingDeletion = null) }

    fun commitPending() {
        val id = _state.value.pendingDeletion ?: return
        loaded = loaded.filter { it.captureId != id }
        publish { it.copy(pendingDeletion = null) }
        commitScope.launch(io) {
            store()?.delete(id)
            onDeleted(id)
        }
    }

    /** Deletes every capture seen on [date] (`CaptureStore.deleteByRange`), then reloads. */
    fun deleteDay(date: LocalDate) {
        commitPending()
        val zone = zone()
        val from = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        val deletion = commitScope.launch(io) { store()?.deleteByRange(from, to) }
        viewModelScope.launch {
            deletion.join()
            reload()
        }
    }

    override fun onCleared() = commitPending()

    private fun reload() {
        generation++
        requesting = false
        loaded = emptyList()
        cursor = null
        _state.update { it.copy(days = emptyList(), loading = true, endReached = false, pendingDeletion = null) }
        viewModelScope.launch {
            val apps = withContext(io) { store()?.packages()?.map { JournalApp(it, labels.label(it)) } }
            _state.update { it.copy(apps = apps.orEmpty()) }
        }
        loadMore()
    }

    private fun publish(change: (JournalUiState) -> JournalUiState) = _state.update { current ->
        val next = change(current)
        val visible = next.pendingDeletion?.let { hidden -> loaded.filter { it.captureId != hidden } } ?: loaded
        next.copy(days = byDay(visible, zone()))
    }

    companion object {
        /** docs/specs/ui-ux.md §3.4. */
        const val PAGE_SIZE = 50
    }
}
