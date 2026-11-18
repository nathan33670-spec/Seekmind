package fr.corsicajack.seekmind.engine.monetization

import android.app.Activity
import fr.corsicajack.seekmind.core.common.SmLog
import fr.corsicajack.seekmind.core.model.RewardOutcome
import fr.corsicajack.seekmind.core.model.RewardReadiness
import fr.corsicajack.seekmind.core.model.port.RewardProvider

/** What the credits sheet tells the user after an attempt (docs/specs/monetisation.md §3.2). */
sealed interface RewardMessage {
    /** « +5 recherches »: [credits] may be below 5 when the balance ceiling truncated it. */
    data class Earned(val credits: Int) : RewardMessage

    /** « Regardez la publicité jusqu'au bout pour obtenir vos recherches ». */
    data object Dismissed : RewardMessage

    /** « Aucune publicité disponible : 1 recherche offerte ». */
    data class Courtesy(val credits: Int) : RewardMessage

    /** « Publicité indisponible pour le moment »: nothing credited. */
    data object Unavailable : RewardMessage

    /** The daily ceiling of rewards is reached: nothing asked of the ad provider. */
    data object CeilingReached : RewardMessage
}

/** State of an ad between the opening of the sheet and the tap on its button. */
sealed interface RewardPreparation {
    /** No ad in this variant: no button at all. */
    data object NotSupported : RewardPreparation

    /** The daily ceiling of rewards is reached: the button is disabled, with the explanation. */
    data object CeilingReached : RewardPreparation

    data class Prepared(val readiness: RewardReadiness) : RewardPreparation
}

/**
 * The rewarded-ad flow (docs/specs/monetisation.md §3.2). [prepare] runs when the credits sheet opens,
 * never before (§3.4); [complete] runs on the tap of « Regarder une publicité (+5 recherches) »:
 *
 * | Readiness, outcome | Credit | Message |
 * |---|---|---|
 * | READY, EARNED | +5, truncated to the balance ceiling | [RewardMessage.Earned] |
 * | READY, DISMISSED | none | [RewardMessage.Dismissed] |
 * | NO_FILL, OFFLINE | +1 of courtesy, 3 a day at most | [RewardMessage.Courtesy], or [RewardMessage.Unavailable] past the ceiling |
 * | NOT_ALLOWED, ERROR; READY, FAILED | none | [RewardMessage.Unavailable] |
 *
 * [provider] is null in a variant without ads.
 */
class RewardFlow(private val ledger: CreditLedger, private val provider: RewardProvider?) {
    val isSupported: Boolean get() = provider?.isSupported == true

    suspend fun prepare(activity: Activity): RewardPreparation {
        val ads = provider?.takeIf { it.isSupported } ?: return RewardPreparation.NotSupported
        if (ledger.rewardsLeftToday() <= 0) return RewardPreparation.CeilingReached
        return RewardPreparation.Prepared(ads.prepare(activity))
    }

    suspend fun complete(activity: Activity, preparation: RewardPreparation): RewardMessage {
        val ads = provider?.takeIf { it.isSupported } ?: return RewardMessage.Unavailable
        val readiness = when (preparation) {
            RewardPreparation.NotSupported -> return RewardMessage.Unavailable
            RewardPreparation.CeilingReached -> return RewardMessage.CeilingReached
            is RewardPreparation.Prepared -> preparation.readiness
        }
        val message = when (readiness) {
            RewardReadiness.READY -> when (ads.show(activity)) {
                RewardOutcome.EARNED -> when (val grant = ledger.reward()) {
                    is CreditGrant.Credited -> RewardMessage.Earned(grant.amount)
                    CreditGrant.DailyCeilingReached -> RewardMessage.CeilingReached
                }

                RewardOutcome.DISMISSED -> RewardMessage.Dismissed

                RewardOutcome.FAILED -> RewardMessage.Unavailable
            }

            RewardReadiness.NO_FILL, RewardReadiness.OFFLINE -> when (val grant = ledger.courtesy()) {
                is CreditGrant.Credited -> RewardMessage.Courtesy(grant.amount)
                CreditGrant.DailyCeilingReached -> RewardMessage.Unavailable
            }

            RewardReadiness.NOT_ALLOWED, RewardReadiness.ERROR -> RewardMessage.Unavailable
        }
        SmLog.i(TAG, "reward_flow", "readiness" to readiness.ordinal)
        return message
    }

    private companion object {
        const val TAG = "RewardFlow"
    }
}
