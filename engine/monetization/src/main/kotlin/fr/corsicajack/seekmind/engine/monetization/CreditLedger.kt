package fr.corsicajack.seekmind.engine.monetization

import fr.corsicajack.seekmind.core.common.Clock
import fr.corsicajack.seekmind.core.database.store.CreditMovement
import fr.corsicajack.seekmind.core.database.store.CreditReason
import fr.corsicajack.seekmind.core.database.store.MonetizationStore
import fr.corsicajack.seekmind.core.model.config.MonetizationConfig
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Result of a reward or courtesy credit (docs/specs/monetisation.md §2, §3.2). */
sealed interface CreditGrant {
    /** [amount] credits were added; it is below the nominal amount when the balance ceiling truncated it. */
    data class Credited(val amount: Int) : CreditGrant

    /** The daily ceiling of this kind of credit is reached: nothing was recorded. */
    data object DailyCeilingReached : CreditGrant
}

/**
 * The credit ledger (docs/specs/monetisation.md §2, §4): the balance is the sum of the movements of
 * [MonetizationStore]; this class holds the rules (welcome credits, ceilings by calendar day, daily
 * free credits, free repeat of a query). Calendar days are those of [zone], read at each call so that
 * a change of time zone is followed. One instance per process: its writes are serialised by a mutex.
 */
class CreditLedger(
    private val store: MonetizationStore,
    private val config: MonetizationConfig,
    private val clock: Clock,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private val mutex = Mutex()

    /** Current balance, updated on every movement. */
    val balance: Flow<Int> = store.observeBalance()

    suspend fun balance(): Int = store.balance()

    /** Records the welcome credits once per database (a new database after a wipe gets them again). */
    suspend fun ensureWelcome(): Boolean = mutex.withLock {
        if (store.hasAny(CreditReason.WELCOME)) return@withLock false
        record(CreditReason.WELCOME, capped(config.welcomeCredits, store.balance()), ref = null)
        true
    }

    /**
     * Daily free credits (`offline` variant): at the first call of the calendar day, tops the balance
     * up to [MonetizationConfig.dailyFreeCredits], without accumulation. Returns the credits added.
     * Nothing is recorded when the setting is 0.
     */
    suspend fun topUpDaily(): Int = mutex.withLock {
        val target = config.dailyFreeCredits
        if (target <= 0) return@withLock 0
        if (store.countSince(CreditReason.DAILY, startOfToday()) > 0) return@withLock 0
        val balance = store.balance()
        val delta = capped((target - balance).coerceAtLeast(0), balance)
        // Recorded even when 0: the movement marks the day as served.
        record(CreditReason.DAILY, delta, ref = null)
        delta
    }

    /** True when [queryKey] was charged less than [MonetizationConfig.freeRepeatWindowMs] ago. */
    suspend fun chargedRecently(queryKey: String): Boolean {
        val last = store.lastAt(CreditReason.SEARCH, queryKey) ?: return false
        // A charge "in the future" (clock moved back) does not make the query free.
        return clock.nowMillis() - last in 0 until config.freeRepeatWindowMs
    }

    /**
     * Charges 1 credit for [queryKey], unless it was charged recently or the balance is empty (the
     * balance never goes below 0). Returns true when a credit was debited.
     */
    suspend fun charge(queryKey: String): Boolean = mutex.withLock {
        if (chargedRecently(queryKey)) return@withLock false
        if (store.balance() < 1) return@withLock false
        record(CreditReason.SEARCH, -1, ref = queryKey)
        true
    }

    /** Credits a watched ad: at most [MonetizationConfig.maxRewardsPerDay] per calendar day. */
    suspend fun reward(): CreditGrant = grant(CreditReason.REWARD, config.creditsPerReward, config.maxRewardsPerDay)

    /** Credits a courtesy search: at most [MonetizationConfig.courtesyPerDay] per calendar day. */
    suspend fun courtesy(): CreditGrant = grant(CreditReason.COURTESY, config.courtesyCredits, config.courtesyPerDay)

    /** Rewards still allowed today. */
    suspend fun rewardsLeftToday(): Int =
        (config.maxRewardsPerDay - store.countSince(CreditReason.REWARD, startOfToday())).coerceAtLeast(0)

    private suspend fun grant(reason: CreditReason, amount: Int, perDay: Int): CreditGrant = mutex.withLock {
        if (store.countSince(reason, startOfToday()) >= perDay) return@withLock CreditGrant.DailyCeilingReached
        val delta = capped(amount, store.balance())
        // Recorded even when truncated to 0: the ad was watched and counts towards the daily ceiling.
        record(reason, delta, ref = null)
        CreditGrant.Credited(delta)
    }

    /** [amount] truncated so that the balance stays at most [MonetizationConfig.maxBalance]. */
    private fun capped(amount: Int, balance: Int): Int =
        amount.coerceAtMost(config.maxBalance - balance).coerceAtLeast(0)

    private suspend fun record(reason: CreditReason, delta: Int, ref: String?) =
        store.append(CreditMovement(at = clock.nowMillis(), delta = delta, reason = reason, ref = ref))

    private fun startOfToday(): Long {
        val zone = zone()
        return Instant.ofEpochMilli(clock.nowMillis()).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()
            .toEpochMilli()
    }
}
