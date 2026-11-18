package fr.corsicajack.seekmind.engine.monetization

import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.database.store.CreditMovement
import fr.corsicajack.seekmind.core.database.store.CreditReason
import fr.corsicajack.seekmind.core.model.RewardOutcome
import fr.corsicajack.seekmind.core.model.RewardReadiness
import fr.corsicajack.seekmind.core.model.config.MonetizationConfig
import fr.corsicajack.seekmind.core.testing.FakeClock
import fr.corsicajack.seekmind.core.testing.FakeMonetizationStore
import fr.corsicajack.seekmind.core.testing.FakeRewardProvider
import fr.corsicajack.seekmind.core.testing.FakeRewardProvider.Companion.ACTIVITY
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** Each outcome of docs/specs/monetisation.md §3.2: the movement and the message (ticket M8-04). */
class RewardFlowTest {
    private val store = FakeMonetizationStore()
    private val clock = FakeClock(now = 1_794_441_600_000L)
    private val config = MonetizationConfig()
    private val ledger = CreditLedger(store, config, clock) { ZoneOffset.UTC }
    private val ads = FakeRewardProvider()
    private val flow = RewardFlow(ledger, ads)

    private fun attempt(): RewardMessage = runBlocking { flow.complete(ACTIVITY, flow.prepare(ACTIVITY)) }

    private fun moves(reason: CreditReason) = store.all.filter { it.reason == reason }.map { it.delta }

    @Test
    fun `an earned reward credits five searches`() {
        assertThat(attempt()).isEqualTo(RewardMessage.Earned(5))
        assertThat(moves(CreditReason.REWARD)).containsExactly(5)
    }

    @Test
    fun `a reward is truncated to the balance ceiling`() = runBlocking<Unit> {
        store.append(CreditMovement(0, config.maxBalance - 2, CreditReason.WELCOME, null))
        assertThat(attempt()).isEqualTo(RewardMessage.Earned(2))
    }

    @Test
    fun `a dismissed ad credits nothing`() {
        ads.outcome = RewardOutcome.DISMISSED
        assertThat(attempt()).isEqualTo(RewardMessage.Dismissed)
        assertThat(store.all).isEmpty()
    }

    @Test
    fun `no fill and offline give one courtesy search, three a day at most`() {
        for (readiness in listOf(RewardReadiness.NO_FILL, RewardReadiness.OFFLINE)) {
            ads.readiness = readiness
            assertThat(attempt()).isEqualTo(RewardMessage.Courtesy(1))
        }
        assertThat(attempt()).isEqualTo(RewardMessage.Courtesy(1))
        assertThat(attempt()).isEqualTo(RewardMessage.Unavailable)
        assertThat(moves(CreditReason.COURTESY)).containsExactly(1, 1, 1)
        assertThat(ads.shows).isEqualTo(0)
    }

    @Test
    fun `not allowed, error and a failed display credit nothing`() {
        ads.readiness = RewardReadiness.NOT_ALLOWED
        assertThat(attempt()).isEqualTo(RewardMessage.Unavailable)
        ads.readiness = RewardReadiness.ERROR
        assertThat(attempt()).isEqualTo(RewardMessage.Unavailable)
        ads.readiness = RewardReadiness.READY
        ads.outcome = RewardOutcome.FAILED
        assertThat(attempt()).isEqualTo(RewardMessage.Unavailable)
        assertThat(store.all).isEmpty()
    }

    @Test
    fun `past the daily ceiling of rewards no ad is prepared`() = runBlocking<Unit> {
        repeat(config.maxRewardsPerDay) { store.append(CreditMovement(clock.now, 0, CreditReason.REWARD, null)) }
        assertThat(flow.prepare(ACTIVITY)).isEqualTo(RewardPreparation.CeilingReached)
        assertThat(attempt()).isEqualTo(RewardMessage.CeilingReached)
        assertThat(ads.prepares).isEqualTo(0)
    }

    @Test
    fun `without ads nothing is prepared nor shown`() = runBlocking<Unit> {
        for (none in listOf(RewardFlow(ledger, null), RewardFlow(ledger, FakeRewardProvider(isSupported = false)))) {
            assertThat(none.isSupported).isFalse()
            assertThat(none.prepare(ACTIVITY)).isEqualTo(RewardPreparation.NotSupported)
            assertThat(none.complete(ACTIVITY, RewardPreparation.Prepared(RewardReadiness.READY)))
                .isEqualTo(RewardMessage.Unavailable)
        }
        assertThat(store.all).isEmpty()
    }
}
