package fr.corsicajack.seekmind.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.corsicajack.seekmind.core.model.Entitlement
import fr.corsicajack.seekmind.engine.monetization.license.ActivationCode
import fr.corsicajack.seekmind.engine.monetization.license.ActivationRefusal
import fr.corsicajack.seekmind.engine.monetization.license.ActivationResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the last action of the « Activation » section did. */
sealed interface ActivationMessage {
    data object Activated : ActivationMessage

    data object Removed : ActivationMessage

    data class Refused(val reason: ActivationRefusal) : ActivationMessage
}

/** State of [ActivationSettingsSection]; [input] is the code being typed or pasted, in memory only. */
data class ActivationUiState(
    val entitlement: Entitlement,
    val input: String,
    val busy: Boolean,
    val message: ActivationMessage?,
)

/**
 * The « Activation » section (docs/specs/ui-ux.md §3.5, docs/specs/monetisation.md §5.4): the balance or
 * the licence, a field to paste a code, and « Retirer l'activation ».
 */
class ActivationSettingsViewModel(
    entitlement: StateFlow<Entitlement>,
    private val activate: suspend (String) -> ActivationResult,
    private val deactivate: suspend () -> Unit,
) : ViewModel() {
    private data class Local(val input: String = "", val busy: Boolean = false, val message: ActivationMessage? = null)

    private val local = MutableStateFlow(Local())

    val state: StateFlow<ActivationUiState> = combine(entitlement, local) { current, it ->
        ActivationUiState(current, it.input, it.busy, it.message)
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        ActivationUiState(entitlement.value, "", busy = false, message = null),
    )

    fun setInput(text: String) {
        local.update { it.copy(input = text, message = null) }
    }

    /** Activates the code of the field; the field is emptied only on success. */
    fun activate() {
        val current = local.value
        if (current.busy) return
        val code = ActivationCode.fromInput(current.input) ?: run {
            local.update { it.copy(message = ActivationMessage.Refused(ActivationRefusal.UNREADABLE)) }
            return
        }
        local.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            val message = when (val result = activate(code)) {
                is ActivationResult.Activated -> ActivationMessage.Activated
                is ActivationResult.Refused -> ActivationMessage.Refused(result.reason)
            }
            local.update {
                it.copy(
                    input = if (message == ActivationMessage.Activated) "" else it.input,
                    busy = false,
                    message = message,
                )
            }
        }
    }

    fun deactivate() {
        if (local.value.busy) return
        local.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            deactivate.invoke()
            local.update { it.copy(busy = false, message = ActivationMessage.Removed) }
        }
    }
}
