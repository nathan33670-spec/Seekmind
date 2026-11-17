package fr.corsicajack.seekmind.link

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.corsicajack.seekmind.engine.monetization.license.ActivationCode
import fr.corsicajack.seekmind.engine.monetization.license.ActivationResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Steps of an activation link (docs/specs/ui-ux.md §3.7). */
sealed interface LinkStep {
    /** Not an activation link of SeekMind: a neutral message, nothing done. */
    data object Invalid : LinkStep

    /** « Activer SeekMind sur ce téléphone ? »: nothing is done before the user confirms. */
    data object Confirm : LinkStep

    data object Working : LinkStep

    data class Done(val result: ActivationResult) : LinkStep

    /** The user declined: nothing done. */
    data object Declined : LinkStep
}

/**
 * The activation by link (docs/specs/monetisation.md §5.4): [link] is the data of the received intent,
 * validated by [ActivationCode.fromLink]; the code is activated only on [confirm].
 */
class LinkViewModel(link: String?, private val activate: suspend (String) -> ActivationResult) : ViewModel() {
    private val code = ActivationCode.fromLink(link)
    private val current = MutableStateFlow(if (code == null) LinkStep.Invalid else LinkStep.Confirm)

    val step: StateFlow<LinkStep> = current.asStateFlow()

    fun confirm() {
        val token = code ?: return
        if (current.value != LinkStep.Confirm) return
        current.value = LinkStep.Working
        viewModelScope.launch { current.value = LinkStep.Done(activate(token)) }
    }

    fun decline() {
        if (current.value == LinkStep.Confirm) current.value = LinkStep.Declined
    }
}
