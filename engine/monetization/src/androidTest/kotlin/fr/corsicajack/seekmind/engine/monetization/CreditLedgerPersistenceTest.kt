package fr.corsicajack.seekmind.engine.monetization

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.database.store.CreditReason
import fr.corsicajack.seekmind.core.model.Authorization
import fr.corsicajack.seekmind.core.model.config.MonetizationConfig
import fr.corsicajack.seekmind.core.testing.FakeClock
import fr.corsicajack.seekmind.core.testing.TemporaryDatabase
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** The balance lives in the SQLCipher database: it survives a restart of the process (M8-01). */
@RunWith(AndroidJUnit4::class)
class CreditLedgerPersistenceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val clock = FakeClock()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var database = TemporaryDatabase(context, NAME)

    @After
    fun tearDown() {
        scope.cancel()
        database.delete()
    }

    @Test
    fun balanceAndCeilingsSurviveReopening() = runBlocking<Unit> {
        val config = MonetizationConfig(courtesyPerDay = 1)
        val first = database.open().monetization
        val gate = LedgerEntitlementGate(CreditLedger(first, config, clock) { ZoneOffset.UTC }, first, scope)
        assertThat(gate.authorize("chat")).isEqualTo(Authorization.Granted(willCharge = true))
        gate.commit("chat", resultCount = 2)
        CreditLedger(first, config, clock) { ZoneOffset.UTC }.courtesy()
        assertThat(first.balance()).isEqualTo(10)

        // A new process: the database is closed, then reopened with its key; new engine objects.
        database.open().close()
        database = TemporaryDatabase(context, NAME)
        val second = database.open().monetization
        val ledger = CreditLedger(second, config, clock) { ZoneOffset.UTC }
        val reopened = LedgerEntitlementGate(ledger, second, scope)

        assertThat(second.balance()).isEqualTo(10)
        assertThat(ledger.ensureWelcome()).isFalse()
        assertThat(second.countSince(CreditReason.WELCOME, since = 0)).isEqualTo(1)
        // The free repeat window and the daily ceilings are read from the database too.
        assertThat(reopened.authorize("chat")).isEqualTo(Authorization.Granted(willCharge = false))
        assertThat(ledger.courtesy()).isEqualTo(CreditGrant.DailyCeilingReached)
    }

    private companion object {
        const val NAME = "monetization-persistence"
    }
}
