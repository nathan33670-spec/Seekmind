package fr.corsicajack.seekmind.feature.paywall

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.corsicajack.seekmind.engine.monetization.RewardFlow
import fr.corsicajack.seekmind.engine.monetization.RewardMessage
import fr.corsicajack.seekmind.engine.monetization.RewardPreparation
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** State of the ad button of the credits sheet. */
enum class AdButton {
    /** No ad in this variant: the button is not composed. */
    ABSENT,

    /** `prepare` is running: loading indicator, button disabled. */
    LOADING,
    READY,

    /** The daily ceiling of rewards is reached: disabled, with the explanation. */
    CEILING,

    /** The ad is shown. */
    WATCHING,
}

data class CreditsSheetState(val button: AdButton, val message: RewardMessage? = null)

/**
 * The credits sheet (docs/specs/monetisation.md §6): the ad is prepared when the sheet opens, never
 * before, and shown on the tap of its button. After an ad that credited nothing, the next one is
 * prepared. [onCredited] runs after a reward or a courtesy credit: the search can go on.
 */
class CreditsSheetViewModel(private val flow: RewardFlow, private val onCredited: () -> Unit) : ViewModel() {
    private val current = MutableStateFlow(
        CreditsSheetState(if (flow.isSupported) AdButton.LOADING else AdButton.ABSENT),
    )
    val state: StateFlow<CreditsSheetState> = current.asStateFlow()

    private var preparation: RewardPreparation? = null
    private var job: Job? = null

    /** Called when the sheet opens: prepares an ad, once. */
    fun open(activity: Activity) {
        if (!flow.isSupported || preparation != null || job?.isActive == true) return
        prepare(activity, message = null)
    }

    fun watch(activity: Activity) {
        val prepared = preparation ?: return
        if (current.value.button != AdButton.READY) return
        current.update { it.copy(button = AdButton.WATCHING, message = null) }
        job = viewModelScope.launch {
            val message = flow.complete(activity, prepared)
            preparation = null
            when (message) {
                is RewardMessage.Earned, is RewardMessage.Courtesy -> {
                    current.value = CreditsSheetState(AdButton.READY, message)
                    onCredited()
                }

                RewardMessage.CeilingReached -> current.value = CreditsSheetState(AdButton.CEILING, message)

                RewardMessage.Dismissed, RewardMessage.Unavailable -> prepare(activity, message)
            }
        }
    }

    private fun prepare(activity: Activity, message: RewardMessage?) {
        current.value = CreditsSheetState(AdButton.LOADING, message)
        job = viewModelScope.launch {
            val prepared = flow.prepare(activity)
            preparation = prepared
            current.update {
                it.copy(
                    button = when (prepared) {
                        RewardPreparation.NotSupported -> AdButton.ABSENT
                        RewardPreparation.CeilingReached -> AdButton.CEILING
                        is RewardPreparation.Prepared -> AdButton.READY
                    },
                )
            }
        }
    }
}
