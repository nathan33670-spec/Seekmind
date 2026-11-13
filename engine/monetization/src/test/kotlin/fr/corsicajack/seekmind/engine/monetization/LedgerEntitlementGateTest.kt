package fr.corsicajack.seekmind.engine.monetization

import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.database.store.CreditReason
import fr.corsicajack.seekmind.core.database.store.License
import fr.corsicajack.seekmind.core.model.Authorization
import fr.corsicajack.seekmind.core.model.Entitlement
import fr.corsicajack.seekmind.core.model.config.MonetizationConfig
import fr.corsicajack.seekmind.core.testing.FakeClock
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test

class LedgerEntitlementGateTest {
    private val store = FakeMonetizationStore()
    private val clock = FakeClock()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun gate(config: MonetizationConfig = MonetizationConfig()) =
        LedgerEntitlementGate(CreditLedger(store, config, clock) { ZoneOffset.UTC }, store, scope)

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun `activated - granted without charge, and commit does nothing`() = runBlocking<Unit> {
        store.setLicense(License("lic-1", "lifetime", "token", activatedAt = clock.now))
        val gate = gate()

        assertThat(gate.authorize("chat")).isEqualTo(Authorization.Granted(willCharge = false))
        gate.commit("chat", resultCount = 3)

        assertThat(store.all.none { it.reason == CreditReason.SEARCH }).isTrue()
        assertThat(gate.awaitState { it is Entitlement.Activated })
            .isEqualTo(Entitlement.Activated("lic-1", "lifetime"))
    }

    @Test
    fun `same query charged less than 10 minutes ago - granted without charge, and commit does nothing`() =
        runBlocking<Unit> {
            val gate = gate()
            gate.authorize("chat")
            gate.commit("chat", resultCount = 3)
            assertThat(store.balance()).isEqualTo(9)

            clock.advance(9 * 60_000)
            assertThat(gate.authorize("chat")).isEqualTo(Authorization.Granted(willCharge = false))
            gate.commit("chat", resultCount = 3)
            assertThat(store.balance()).isEqualTo(9)

            clock.advance(60_000)
            assertThat(gate.authorize("chat")).isEqualTo(Authorization.Granted(willCharge = true))
            gate.commit("chat", resultCount = 3)
            assertThat(store.balance()).isEqualTo(8)
        }

    @Test
    fun `balance at least 1 - granted with charge, commit debits 1`() = runBlocking<Unit> {
        val gate = gate()

        assertThat(gate.authorize("chat")).isEqualTo(Authorization.Granted(willCharge = true))
        gate.commit("chat", resultCount = 1)

        assertThat(store.balance()).isEqualTo(9)
        assertThat(gate.awaitState { it == Entitlement.Free(9) }).isEqualTo(Entitlement.Free(9))
    }

    @Test
    fun `balance 0 - no credit`() = runBlocking<Unit> {
        val gate = gate(MonetizationConfig(welcomeCredits = 1))
        gate.authorize("a")
        gate.commit("a", resultCount = 2)

        assertThat(gate.authorize("b")).isEqualTo(Authorization.NoCredit)
        // A commit without a prior grant cannot make the balance negative.
        gate.commit("b", resultCount = 2)
        assertThat(store.balance()).isEqualTo(0)
    }

    @Test
    fun `commit with zero results does not debit`() = runBlocking<Unit> {
        val gate = gate()
        gate.authorize("chat")

        gate.commit("chat", resultCount = 0)

        assertThat(store.balance()).isEqualTo(10)
        // Not charged, so not free either: the next attempt is a normal one.
        assertThat(gate.authorize("chat")).isEqualTo(Authorization.Granted(willCharge = true))
    }

    @Test
    fun `welcome credits are given when the state is first read`() = runBlocking<Unit> {
        val gate = gate()

        assertThat(gate.awaitState { it == Entitlement.Free(10) }).isEqualTo(Entitlement.Free(10))
        assertThat(store.all.count { it.reason == CreditReason.WELCOME }).isEqualTo(1)
    }

    @Test
    fun `removing the license brings back the free plan with the previous balance`() = runBlocking<Unit> {
        val gate = gate()
        gate.authorize("a")
        gate.commit("a", resultCount = 1)
        store.setLicense(License("lic-1", "lifetime", "token", activatedAt = clock.now))
        gate.awaitState { it is Entitlement.Activated }

        store.clearLicense()

        assertThat(gate.awaitState { it is Entitlement.Free }).isEqualTo(Entitlement.Free(9))
    }

    @Test
    fun `daily credits of 5 - topped up at the first search of the day`() = runBlocking<Unit> {
        val gate = gate(MonetizationConfig(welcomeCredits = 0, dailyFreeCredits = 5))

        assertThat(gate.authorize("a")).isEqualTo(Authorization.Granted(willCharge = true))
        assertThat(store.balance()).isEqualTo(5)
        repeat(5) {
            gate.authorize("q$it")
            gate.commit("q$it", resultCount = 1)
        }
        assertThat(gate.authorize("z")).isEqualTo(Authorization.NoCredit)

        clock.advance(24 * 3_600_000L)
        assertThat(gate.authorize("z")).isEqualTo(Authorization.Granted(willCharge = true))
        assertThat(store.balance()).isEqualTo(5)
    }

    @Test
    fun `daily credits of 0 - nothing is added`() = runBlocking<Unit> {
        val gate = gate(MonetizationConfig(welcomeCredits = 0, dailyFreeCredits = 0))

        assertThat(gate.authorize("a")).isEqualTo(Authorization.NoCredit)
        clock.advance(24 * 3_600_000L)
        assertThat(gate.authorize("a")).isEqualTo(Authorization.NoCredit)
        assertThat(store.balance()).isEqualTo(0)
    }

    private suspend fun LedgerEntitlementGate.awaitState(predicate: (Entitlement) -> Boolean): Entitlement =
        withTimeout(5_000) { state.first(predicate) }
}
