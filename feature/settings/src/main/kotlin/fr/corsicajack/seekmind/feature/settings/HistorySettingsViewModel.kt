package fr.corsicajack.seekmind.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.corsicajack.seekmind.core.database.settings.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** State of [HistorySettingsSection]. */
data class HistoryUiState(val enabled: Boolean, val cleared: Boolean)

/** « Recherche » (docs/specs/ui-ux.md §3.5, EF-SRCH-09): the history, its switch and its deletion. */
class HistorySettingsViewModel(
    settings: Flow<Settings>,
    private val setEnabled: suspend (Boolean) -> Unit,
    private val clear: suspend () -> Unit,
) : ViewModel() {
    private val cleared = MutableStateFlow(false)

    val state: StateFlow<HistoryUiState?> = combine(settings, cleared) { values, done ->
        HistoryUiState(values.historyEnabled, done)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch { setEnabled.invoke(enabled) }
    }

    fun clear() {
        viewModelScope.launch {
            clear.invoke()
            cleared.value = true
        }
    }

    fun clearedShown() {
        cleared.value = false
    }
}
