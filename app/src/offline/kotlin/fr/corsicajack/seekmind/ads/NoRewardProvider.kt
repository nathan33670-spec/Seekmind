package fr.corsicajack.seekmind.ads

import android.app.Activity
import fr.corsicajack.seekmind.core.model.RewardOutcome
import fr.corsicajack.seekmind.core.model.RewardReadiness
import fr.corsicajack.seekmind.core.model.port.RewardProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The `offline` variant has no ad (docs/specs/monetisation.md §4, ADR-0009): [isSupported] is false, so
 * the credits sheet only offers the activation code. Never called otherwise; answers « not allowed ».
 */
object NoRewardProvider : RewardProvider {
    override val isSupported: Boolean = false

    override suspend fun prepare(activity: Activity): RewardReadiness = RewardReadiness.NOT_ALLOWED

    override suspend fun show(activity: Activity): RewardOutcome = RewardOutcome.FAILED

    override val privacyOptionsRequired: StateFlow<Boolean> = MutableStateFlow(false)

    override suspend fun showPrivacyOptions(activity: Activity) = Unit
}
