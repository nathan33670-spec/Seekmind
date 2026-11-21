package fr.corsicajack.seekmind.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.corsicajack.seekmind.core.model.DropReason
import java.time.LocalDate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Counters of `metric_daily` (architecture §11): a constant key, a number. Only keys made of lower-case
 * letters, digits and underscores are kept, so that nothing but a counter name can reach the screen or
 * the shared text.
 */
data class Counters(val values: Map<String, Long>) {
    val stored: Long get() = values[STORED] ?: 0
    val deduplicated: Long get() = values[DEDUPLICATED] ?: 0

    /** Rejections by reason, the most frequent first. */
    val dropped: List<Pair<DropReason, Long>>
        get() = DropReason.entries.mapNotNull { reason -> values[dropKey(reason)]?.let { reason to it } }
            .sortedByDescending { it.second }

    companion object {
        const val STORED = "capture_stored"
        const val DEDUPLICATED = "capture_deduplicated"
        private val KEY = Regex("^[a-z0-9_]{1,64}$")

        fun dropKey(reason: DropReason) = "capture_dropped_${reason.name.lowercase()}"

        fun of(values: Map<String, Long>) = Counters(values.filterKeys(KEY::matches).toSortedMap())

        /** The sum of several days, key by key. */
        fun sum(days: Collection<Map<String, Long>>) =
            of(days.flatMap { it.entries }.groupBy({ it.key }, { it.value }).mapValues { (_, v) -> v.sum() })
    }
}

/** State of [DiagnosticSettingsSection]. */
data class DiagnosticUiState(val today: Counters, val lastWeek: Counters, val pendingFragments: Int)

/**
 * « Diagnostic » (docs/specs/ui-ux.md §3.5): the counters of the day and of the last 7 days, and the text
 * the user may share. Neither holds any content: counter names and numbers only (INV-07).
 */
class DiagnosticSettingsViewModel(
    /** Counters of the days from the first to the second included, by day then by key. */
    private val metrics: suspend (LocalDate, LocalDate) -> Map<String, Map<String, Long>>,
    private val pendingFragments: suspend () -> Int,
    private val today: () -> LocalDate,
    private val io: CoroutineDispatcher,
) : ViewModel() {
    private val mutable = MutableStateFlow<DiagnosticUiState?>(null)
    val state: StateFlow<DiagnosticUiState?> = mutable.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            val day = today()
            mutable.value = withContext(io) {
                val week = runCatching { metrics(day.minusDays(DAYS - 1), day) }.getOrDefault(emptyMap())
                DiagnosticUiState(
                    today = Counters.of(week[day.toString()].orEmpty()),
                    lastWeek = Counters.sum(week.values),
                    pendingFragments = runCatching { pendingFragments() }.getOrDefault(0),
                )
            }
        }
    }

    companion object {
        const val DAYS = 7L

        /**
         * The shared text after its title: the version, the Android level, the counters. Counter names
         * and numbers only.
         */
        fun report(state: DiagnosticUiState, version: String, sdk: Int): String = buildString {
            appendLine("version=$version android=$sdk")
            appendLine("pending_fragments=${state.pendingFragments}")
            appendLine("[today]")
            state.today.values.forEach { (key, value) -> appendLine("$key=$value") }
            appendLine("[last_${DAYS}_days]")
            state.lastWeek.values.forEach { (key, value) -> appendLine("$key=$value") }
        }.trimEnd()
    }
}
