package fr.corsicajack.seekmind.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.corsicajack.seekmind.core.database.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** State of [SecuritySettingsSection]. */
data class SecurityUiState(val lockEnabled: Boolean, val graceMs: Long, val deviceSecure: Boolean)

/**
 * The lock settings. [isDeviceSecure] is read again by [refresh], when the screen comes back to the
 * foreground (the user may have just set a screen lock). [onLockTurnedOn] keeps the current session
 * open when the user turns the lock on: they are using the application already.
 */
class SecuritySettingsViewModel(
    private val settings: SettingsRepository,
    private val isDeviceSecure: () -> Boolean,
    private val onLockTurnedOn: () -> Unit,
) : ViewModel() {
    private val deviceSecure = MutableStateFlow(isDeviceSecure())

    val state: StateFlow<SecurityUiState?> = combine(settings.settings, deviceSecure) { current, secure ->
        SecurityUiState(lockEnabled = current.lockEnabled, graceMs = current.lockGraceMs, deviceSecure = secure)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    fun refresh() {
        deviceSecure.value = isDeviceSecure()
    }

    fun setLockEnabled(enabled: Boolean) {
        if (enabled && !deviceSecure.value) return
        viewModelScope.launch {
            if (enabled) onLockTurnedOn()
            settings.setLockEnabled(enabled)
        }
    }

    fun setGraceMs(ms: Long) {
        require(ms in GRACE_CHOICES_MS) { "grace of $ms ms" }
        viewModelScope.launch { settings.setLockGraceMs(ms) }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
