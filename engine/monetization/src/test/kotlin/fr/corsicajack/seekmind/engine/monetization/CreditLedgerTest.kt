package fr.corsicajack.seekmind.engine.monetization

import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.database.store.CreditMovement
import fr.corsicajack.seekmind.core.database.store.CreditReason
import fr.corsicajack.seekmind.core.model.config.MonetizationConfig
import fr.corsicajack.seekmind.core.testing.FakeClock
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Test

class CreditLedgerTest {
    private val store = FakeMonetizationStore()

    // 2026-11-12T00:00:00Z.
    private val clock = FakeClock(now = MIDNIGHT_UTC)

    private fun ledger(config: MonetizationConfig = MonetizationConfig(), zone: ZoneId = ZoneOffset.UTC) =
        CreditLedger(store, config, clock) { zone }

    @Test
    fun `welcome credits are given once`() = runBlocking<Unit> {
        val ledger = ledger()

        assertThat(ledger.ensureWelcome()).isTrue()
        assertThat(ledger.ensureWelcome()).isFalse()

        assertThat(ledger.balance()).isEqualTo(10)
        assertThat(store.all.map { it.reason }).containsExactly(CreditReason.WELCOME)
    }

    @Test
    fun `charge debits one credit and records the query key`() = runBlocking<Unit> {
        val ledger = ledger()
        ledger.ensureWelcome()

        assertThat(ledger.charge("chat")).isTrue()

        assertThat(ledger.balance()).isEqualTo(9)
        assertThat(store.all.last()).isEqualTo(CreditMovement(MIDNIGHT_UTC, -1, CreditReason.SEARCH, "chat"))
    }

    @Test
    fun `the same query is free for 10 minutes, then charged again`() = runBlocking<Unit> {
        val ledger = ledger()
        ledger.ensureWelcome()
        ledger.charge("chat")

        clock.advance(599_999)
        assertThat(ledger.chargedRecently("chat")).isTrue()
        assertThat(ledger.charge("chat")).isFalse()
        assertThat(ledger.chargedRecently("other")).isFalse()

        clock.advance(1)
        assertThat(ledger.chargedRecently("chat")).isFalse()
        assertThat(ledger.charge("chat")).isTrue()
        assertThat(ledger.balance()).isEqualTo(8)
    }

    @Test
    fun `a charge dated in the future does not make the query free`() = runBlocking<Unit> {
        val ledger = ledger()
        ledger.ensureWelcome()
        ledger.charge("chat")

        clock.now -= 60_000

        assertThat(ledger.chargedRecently("chat")).isFalse()
    }

    @Test
    fun `the balance never goes below zero`() = runBlocking<Unit> {
        val ledger = ledger(MonetizationConfig(welcomeCredits = 1))
        ledger.ensureWelcome()

        assertThat(ledger.charge("a")).isTrue()
        assertThat(ledger.charge("b")).isFalse()

        assertThat(ledger.balance()).isEqualTo(0)
    }

    @Test
    fun `a reward adds 5 credits`() = runBlocking<Unit> {
        val ledger = ledger()
        ledger.ensureWelcome()

        assertThat(ledger.reward()).isEqualTo(CreditGrant.Credited(5))

        assertThat(ledger.balance()).isEqualTo(15)
    }

    @Test
    fun `a reward is truncated at the balance ceiling of 100`() = runBlocking<Unit> {
        val ledger = ledger()
        store.append(CreditMovement(MIDNIGHT_UTC, 97, CreditReason.WELCOME, null))

        assertThat(ledger.reward()).isEqualTo(CreditGrant.Credited(3))
        assertThat(ledger.reward()).isEqualTo(CreditGrant.Credited(0))
        assertThat(ledger.courtesy()).isEqualTo(CreditGrant.Credited(0))

        assertThat(ledger.balance()).isEqualTo(100)
    }

    @Test
    fun `at most 20 rewards per calendar day, again the next day`() = runBlocking<Unit> {
        val ledger = ledger(MonetizationConfig(creditsPerReward = 1))
        repeat(20) {
            assertThat(ledger.reward()).isEqualTo(CreditGrant.Credited(1))
            clock.advance(60_000)
        }

        assertThat(ledger.rewardsLeftToday()).isEqualTo(0)
        assertThat(ledger.reward()).isEqualTo(CreditGrant.DailyCeilingReached)
        assertThat(ledger.balance()).isEqualTo(20)

        // 23:59:59.999 the same day: still capped.
        clock.now = MIDNIGHT_UTC + DAY_MS - 1
        assertThat(ledger.reward()).isEqualTo(CreditGrant.DailyCeilingReached)

        clock.now = MIDNIGHT_UTC + DAY_MS
        assertThat(ledger.rewardsLeftToday()).isEqualTo(20)
        assertThat(ledger.reward()).isEqualTo(CreditGrant.Credited(1))
        assertThat(ledger.balance()).isEqualTo(21)
    }

    @Test
    fun `at most 3 courtesy credits per calendar day, again the next day`() = runBlocking<Unit> {
        val ledger = ledger()
        repeat(3) { assertThat(ledger.courtesy()).isEqualTo(CreditGrant.Credited(1)) }

        assertThat(ledger.courtesy()).isEqualTo(CreditGrant.DailyCeilingReached)
        // Courtesy credits do not use the reward ceiling.
        assertThat(ledger.rewardsLeftToday()).isEqualTo(20)

        clock.advance(DAY_MS)
        assertThat(ledger.courtesy()).isEqualTo(CreditGrant.Credited(1))
        assertThat(ledger.balance()).isEqualTo(4)
    }

    @Test
    fun `calendar days follow the local time zone`() = runBlocking<Unit> {
        val paris = ZoneId.of("Europe/Paris")
        val ledger = ledger(MonetizationConfig(courtesyPerDay = 1), paris)
        // 22:30 UTC = 23:30 in Paris (UTC+1 in November).
        clock.now = MIDNIGHT_UTC + 22 * HOUR_MS + 30 * MINUTE_MS
        assertThat(ledger.courtesy()).isEqualTo(CreditGrant.Credited(1))

        // 23:30 UTC = 00:30 the next day in Paris, still the same day in UTC.
        clock.advance(HOUR_MS)

        assertThat(ledger.courtesy()).isEqualTo(CreditGrant.Credited(1))
    }

    @Test
    fun `daily credits top the balance up to 5 once per day, without accumulation`() = runBlocking<Unit> {
        val ledger = ledger(MonetizationConfig(welcomeCredits = 0, dailyFreeCredits = 5))
        ledger.ensureWelcome()

        assertThat(ledger.topUpDaily()).isEqualTo(5)
        assertThat(ledger.balance()).isEqualTo(5)
        assertThat(ledger.topUpDaily()).isEqualTo(0)

        ledger.charge("a")
        ledger.charge("b")
        assertThat(ledger.topUpDaily()).isEqualTo(0)
        assertThat(ledger.balance()).isEqualTo(3)

        clock.advance(DAY_MS)
        assertThat(ledger.topUpDaily()).isEqualTo(2)
        assertThat(ledger.balance()).isEqualTo(5)

        // A balance above the daily amount is kept and the day is served.
        store.append(CreditMovement(clock.now, 10, CreditReason.REWARD, null))
        clock.advance(DAY_MS)
        assertThat(ledger.topUpDaily()).isEqualTo(0)
        assertThat(ledger.balance()).isEqualTo(15)
        repeat(15) { ledger.charge("q$it") }
        assertThat(ledger.topUpDaily()).isEqualTo(0)
        assertThat(ledger.balance()).isEqualTo(0)
    }

    @Test
    fun `no daily credits when the setting is 0`() = runBlocking<Unit> {
        val ledger = ledger(MonetizationConfig(welcomeCredits = 0))
        ledger.ensureWelcome()

        assertThat(ledger.topUpDaily()).isEqualTo(0)
        clock.advance(DAY_MS)
        assertThat(ledger.topUpDaily()).isEqualTo(0)

        assertThat(ledger.balance()).isEqualTo(0)
        assertThat(store.all.none { it.reason == CreditReason.DAILY }).isTrue()
    }

    private companion object {
        const val MIDNIGHT_UTC = 1_794_441_600_000L
        const val MINUTE_MS = 60_000L
        const val HOUR_MS = 60 * MINUTE_MS
        const val DAY_MS = 24 * HOUR_MS
    }
}
