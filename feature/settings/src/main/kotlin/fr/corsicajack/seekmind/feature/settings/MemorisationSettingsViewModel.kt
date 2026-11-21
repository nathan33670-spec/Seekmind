package fr.corsicajack.seekmind.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.corsicajack.seekmind.core.database.settings.Settings
import fr.corsicajack.seekmind.core.model.CaptureStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The pauses offered (docs/specs/capture.md §7). */
enum class PauseChoice { ONE_HOUR, UNTIL_TOMORROW_MORNING, INDEFINITELY }

/** State of [MemorisationSettingsSection]. */
data class MemorisationUiState(
    val status: CaptureStatus,
    val consented: Boolean,
    /** The consent was just withdrawn: the screen offers to erase the data too. */
    val offerWipe: Boolean = false,
)

/**
 * « Mémorisation » (docs/specs/ui-ux.md §3.5): the state of the service, the pauses, the resumption and the
 * withdrawal of the consent. Withdrawing stops the capture at once: the pipeline and the router read the
 * consent (INV-04); the data stay until the user erases them.
 */
class MemorisationSettingsViewModel(
    status: Flow<CaptureStatus>,
    settings: Flow<Settings>,
    private val pause: suspend (PauseChoice) -> Unit,
    private val resume: suspend () -> Unit,
    private val withdrawConsent: suspend () -> Unit,
) : ViewModel() {
    private val offerWipe = MutableStateFlow(false)

    val state: StateFlow<MemorisationUiState?> = combine(status, settings, offerWipe) { current, values, offer ->
        MemorisationUiState(current, values.hasCurrentConsent(), offer)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    fun pause(choice: PauseChoice) {
        viewModelScope.launch { pause.invoke(choice) }
    }

    fun resume() {
        viewModelScope.launch { resume.invoke() }
    }

    fun withdraw() {
        viewModelScope.launch {
            withdrawConsent()
            offerWipe.value = true
        }
    }

    fun dismissWipeOffer() {
        offerWipe.value = false
    }
}

/** How long a state stays subscribed after its last collector (a rotation keeps it). */
internal const val STOP_TIMEOUT_MS = 5_000L
