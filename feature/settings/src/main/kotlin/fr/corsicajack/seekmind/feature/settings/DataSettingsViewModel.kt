package fr.corsicajack.seekmind.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.corsicajack.seekmind.core.database.settings.Settings
import fr.corsicajack.seekmind.core.database.store.StorageStats
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The periods offered by « Supprimer par période », counted back from now. */
enum class DeletePeriod(val durationMs: Long) {
    LAST_HOUR(HOUR_MS),
    LAST_DAY(24 * HOUR_MS),
    LAST_7_DAYS(7 * 24 * HOUR_MS),
    LAST_30_DAYS(30 * 24 * HOUR_MS),
}

private const val HOUR_MS = 3_600_000L

/** What the last deletion did, for a message. */
sealed interface DataMessage {
    data class PeriodDeleted(val period: DeletePeriod) : DataMessage

    data class AppDeleted(val app: InstalledApp) : DataMessage
}

/** State of [DataSettingsSection]. */
data class DataUiState(
    val retentionDays: Int?,
    val budgetBytes: Long,
    /** null while it is read, or when the database is not open. */
    val stats: StorageStats?,
    /** Applications that have remembered screens, for « Supprimer par application ». */
    val apps: List<InstalledApp>,
    val message: DataMessage?,
)

/**
 * « Données » (docs/specs/ui-ux.md §3.5, docs/specs/stockage.md §6, EF-PRIV-08, EF-PRIV-09): the retention
 * and the budget, read by the next maintenance; what is used; the deletions by period and by application.
 * « Tout effacer » is the screen's: it asks for the confirmation word, then calls the wipe of `:app`.
 */
class DataSettingsViewModel(
    settings: Flow<Settings>,
    private val stats: suspend () -> StorageStats?,
    private val appsWithCaptures: suspend () -> List<InstalledApp>,
    private val setRetentionDays: suspend (Int?) -> Unit,
    private val setBudgetBytes: suspend (Long) -> Unit,
    /** Deletes every capture seen during `[from, to]`. */
    private val deleteRange: suspend (Long, Long) -> Unit,
    private val deletePackage: suspend (String) -> Unit,
    private val now: () -> Long,
    private val io: CoroutineDispatcher,
) : ViewModel() {
    private val usage = MutableStateFlow<Pair<StorageStats?, List<InstalledApp>>>(null to emptyList())
    private val message = MutableStateFlow<DataMessage?>(null)

    val state: StateFlow<DataUiState?> = combine(settings, usage, message) { values, (used, apps), last ->
        DataUiState(values.retentionDays, values.storageBudgetBytes, used, apps, last)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    fun refresh() {
        viewModelScope.launch { reload() }
    }

    fun setRetention(days: Int?) {
        require(days in RETENTION_CHOICES) { "retention of $days days" }
        viewModelScope.launch { setRetentionDays(days) }
    }

    fun setBudget(bytes: Long) {
        require(bytes in BUDGET_CHOICES) { "budget of $bytes bytes" }
        viewModelScope.launch { setBudgetBytes(bytes) }
    }

    fun delete(period: DeletePeriod) {
        viewModelScope.launch {
            val to = now()
            withContext(io) { deleteRange(to - period.durationMs, to) }
            message.value = DataMessage.PeriodDeleted(period)
            reload()
        }
    }

    fun delete(app: InstalledApp) {
        viewModelScope.launch {
            withContext(io) { deletePackage(app.packageName) }
            message.value = DataMessage.AppDeleted(app)
            reload()
        }
    }

    fun messageShown() {
        message.value = null
    }

    private suspend fun reload() {
        usage.value = withContext(io) { stats() to appsWithCaptures() }
    }

    companion object {
        /** EF-PRIV-09: 30, 90, 180, 365 days or unlimited (null). */
        val RETENTION_CHOICES: List<Int?> = listOf(30, 90, 180, 365, null)

        private const val MB = 1024L * 1024

        /** 500 MB by default (EF-PRIV-09). */
        val BUDGET_CHOICES: List<Long> = listOf(250 * MB, 500 * MB, 1024 * MB, 2048 * MB)
    }
}
