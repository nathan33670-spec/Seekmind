package fr.corsicajack.seekmind

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.EntryPointAccessors
import fr.corsicajack.seekmind.core.testing.FakeClock
import fr.corsicajack.seekmind.core.testing.FakeMonetizationStore
import fr.corsicajack.seekmind.di.OfflineEntryPoint
import fr.corsicajack.seekmind.engine.monetization.CreditLedger
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * E2E-15 of the `offline` variant (docs/06-qualite-tests-ci.md §4): no ad, and the balance is topped up
 * to five searches on each calendar day, with the configuration the variant ships. The ledger runs on an
 * in-memory store: a movement written in the real database would change the days of the device.
 */
@RunWith(AndroidJUnit4::class)
class DailyCreditsE2ETest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val graph = EntryPointAccessors.fromApplication(context, OfflineEntryPoint::class.java)

    @Test
    fun theBalanceIsToppedUpToFiveOncePerDayAndNotCumulated() = runBlocking<Unit> {
        assertThat(graph.rewardFlow().isSupported).isFalse()
        val store = FakeMonetizationStore()
        val clock = FakeClock()
        val ledger = CreditLedger(store, graph.monetizationConfig(), clock) { ZoneOffset.UTC }

        assertThat(ledger.topUpDaily()).isEqualTo(5)
        assertThat(ledger.balance()).isEqualTo(5)
        // Once a day only.
        assertThat(ledger.topUpDaily()).isEqualTo(0)
        // The next day, back to five, never above.
        clock.advance(DAY_MS)
        assertThat(ledger.topUpDaily()).isEqualTo(0)
        assertThat(ledger.balance()).isEqualTo(5)
    }

    private companion object {
        const val DAY_MS = 86_400_000L
    }
}
