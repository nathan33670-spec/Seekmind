package fr.corsicajack.seekmind

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.EntryPointAccessors
import fr.corsicajack.seekmind.core.common.Clock
import fr.corsicajack.seekmind.core.database.store.CreditMovement
import fr.corsicajack.seekmind.core.database.store.CreditReason
import fr.corsicajack.seekmind.di.OfflineEntryPoint
import fr.corsicajack.seekmind.engine.monetization.CreditLedger
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * E2E-15 of the `offline` variant (docs/06-qualite-tests-ci.md §4): no ad, the balance is topped up to
 * five searches on each calendar day, on the real database with the configuration of the variant. The
 * day is one far ahead, so that the days of the device are left as they were.
 */
@RunWith(AndroidJUnit4::class)
class DailyCreditsE2ETest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val graph = EntryPointAccessors.fromApplication(context, OfflineEntryPoint::class.java)
    private val store get() = CaptureEntryPointAccess.database(context).monetization

    private class FixedClock(private val now: Long) : Clock {
        override fun nowMillis() = now

        override fun elapsedRealtime() = 0L
    }

    @Test
    fun theBalanceIsToppedUpToFiveOncePerDayAndNotCumulated() = runBlocking<Unit> {
        val before = store.balance()
        store.append(CreditMovement(System.currentTimeMillis(), -before, CreditReason.SEARCH, "e2e"))
        try {
            val day = CreditLedger(store, graph.monetizationConfig(), FixedClock(FAR_DAY)) { ZoneOffset.UTC }
            assertThat(graph.rewardFlow().isSupported).isFalse()
            assertThat(day.topUpDaily()).isEqualTo(5)
            assertThat(store.balance()).isEqualTo(5)
            // Once a day only, and never above five.
            assertThat(day.topUpDaily()).isEqualTo(0)
            val nextDay =
                CreditLedger(store, graph.monetizationConfig(), FixedClock(FAR_DAY + DAY_MS)) { ZoneOffset.UTC }
            assertThat(nextDay.topUpDaily()).isEqualTo(0)
            assertThat(store.balance()).isEqualTo(5)
        } finally {
            store.append(
                CreditMovement(System.currentTimeMillis(), before - store.balance(), CreditReason.WELCOME, "e2e"),
            )
        }
    }

    private companion object {
        /** 2100-01-01T12:00:00Z. */
        const val FAR_DAY = 4_102_488_000_000L
        const val DAY_MS = 86_400_000L
    }
}
