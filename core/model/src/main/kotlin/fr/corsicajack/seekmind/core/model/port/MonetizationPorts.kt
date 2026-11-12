package fr.corsicajack.seekmind.core.model.port

import android.app.Activity
import fr.corsicajack.seekmind.core.model.Authorization
import fr.corsicajack.seekmind.core.model.Entitlement
import fr.corsicajack.seekmind.core.model.RewardOutcome
import fr.corsicajack.seekmind.core.model.RewardReadiness
import kotlinx.coroutines.flow.StateFlow

/** Credits and activation (docs/specs/monetisation.md §2), implemented by `engine:monetization`. */
interface EntitlementGate {
    val state: StateFlow<Entitlement>

    /** To call before running a search confirmed by the user. */
    suspend fun authorize(queryKey: String): Authorization

    /** To call when the hybrid results are shown. Debits when needed. */
    suspend fun commit(queryKey: String, resultCount: Int)
}

/**
 * Rewarded ads (docs/specs/monetisation.md §3): `engine:ads` in the `ads` variant, `NoRewardProvider`
 * in :app for `offline`. **No parameter carries user data** (INV-01, INV-08): an Activity to show the
 * ad on, nothing else.
 */
interface RewardProvider {
    /** False in the `offline` variant: the interface then offers no ad. */
    val isSupported: Boolean

    /** Ad consent if needed, then loading of an ad. */
    suspend fun prepare(activity: Activity): RewardReadiness

    /** Shows the prepared ad. */
    suspend fun show(activity: Activity): RewardOutcome

    /** True when the regulations require offering access to the ad privacy choices. */
    val privacyOptionsRequired: StateFlow<Boolean>

    suspend fun showPrivacyOptions(activity: Activity)
}
