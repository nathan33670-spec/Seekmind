package fr.corsicajack.seekmind.core.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.database.store.AppRule
import fr.corsicajack.seekmind.core.database.store.AppRuleMode
import fr.corsicajack.seekmind.core.database.store.CreditMovement
import fr.corsicajack.seekmind.core.database.store.CreditReason
import fr.corsicajack.seekmind.core.database.store.License
import fr.corsicajack.seekmind.core.database.store.SearchHistoryEntry
import fr.corsicajack.seekmind.core.database.store.SecureProbe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The stores of the small tables (ticket M1-05). */
@RunWith(AndroidJUnit4::class)
class SideStoresTest {
    private val test = TestDatabase("side")
    private lateinit var handle: DatabaseHandle

    @Before
    fun open() {
        test.delete()
        handle = test.handle()
    }

    @After
    fun clean() = test.delete()

    @Test
    fun appRulesAreSetReplacedRemovedAndObserved() = runBlocking {
        val rules = handle.appRules
        assertThat(rules.get("com.example.bank")).isNull()
        rules.set(AppRule("com.example.bank", AppRuleMode.EXCLUDED, 1_000))
        rules.set(AppRule("com.example.app", AppRuleMode.INCLUDED, 1_000))
        rules.set(AppRule("com.example.bank", AppRuleMode.INCLUDED, 2_000))
        assertThat(rules.get("com.example.bank")).isEqualTo(AppRule("com.example.bank", AppRuleMode.INCLUDED, 2_000))
        assertThat(
            rules.observe().first().map {
                it.packageName
            },
        ).containsExactly("com.example.app", "com.example.bank").inOrder()
        rules.remove("com.example.bank")
        rules.remove("com.example.absent")
        assertThat(rules.observe().first().map { it.packageName }).containsExactly("com.example.app")
    }

    @Test
    fun secureProbesAreKeyedByPackageAndActivity() = runBlocking {
        val probes = handle.secureProbes
        probes.put(SecureProbe("com.example.browser", "", isSecure = false, probedAt = 1_000))
        probes.put(SecureProbe("com.example.browser", "a.Private", isSecure = true, probedAt = 2_000))
        assertThat(probes.get("com.example.browser", "")!!.isSecure).isFalse()
        assertThat(probes.get("com.example.browser", "a.Private")!!.isSecure).isTrue()
        probes.put(SecureProbe("com.example.browser", "", isSecure = true, probedAt = 3_000))
        assertThat(probes.get("com.example.browser", "")).isEqualTo(SecureProbe("com.example.browser", "", true, 3_000))
        assertThat(probes.deleteOlderThan(2_500)).isEqualTo(1)
        assertThat(probes.get("com.example.browser", "a.Private")).isNull()
        assertThat(probes.get("com.example.other", "")).isNull()
    }

    @Test
    fun theLedgerSumsMovementsAndAnswersTheRuleQueries() = runBlocking {
        val money = handle.monetization
        assertThat(money.balance()).isEqualTo(0)
        assertThat(money.hasAny(CreditReason.WELCOME)).isFalse()
        money.append(CreditMovement(1_000, +10, CreditReason.WELCOME, null))
        money.append(CreditMovement(2_000, -1, CreditReason.SEARCH, "facture"))
        money.append(CreditMovement(3_000, -1, CreditReason.SEARCH, "facture"))
        money.append(CreditMovement(4_000, +5, CreditReason.REWARD, null))
        money.append(CreditMovement(5_000, +5, CreditReason.REWARD, null))
        assertThat(money.balance()).isEqualTo(18)
        assertThat(money.observeBalance().first()).isEqualTo(18)
        assertThat(money.hasAny(CreditReason.WELCOME)).isTrue()
        assertThat(money.countSince(CreditReason.REWARD, 4_500)).isEqualTo(1)
        assertThat(money.countSince(CreditReason.REWARD, 0)).isEqualTo(2)
        assertThat(money.lastAt(CreditReason.SEARCH, "facture")).isEqualTo(3_000)
        assertThat(money.lastAt(CreditReason.SEARCH, "autre")).isNull()
    }

    @Test
    fun theBalanceIsObservable() = runTest {
        handle.monetization.observeBalance().test {
            assertThat(awaitItem()).isEqualTo(0)
            handle.monetization.append(CreditMovement(1_000, +10, CreditReason.WELCOME, null))
            assertThat(awaitItem()).isEqualTo(10)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun theLicenseIsASingleRow() = runBlocking {
        val money = handle.monetization
        assertThat(money.license()).isNull()
        money.setLicense(License("lid-1", "lifetime", "token-1", 1_000))
        money.setLicense(License("lid-2", "lifetime", "token-2", 2_000))
        assertThat(money.license()).isEqualTo(License("lid-2", "lifetime", "token-2", 2_000))
        assertThat(test.ready().openHelper.writableDatabase.long("SELECT count(*) FROM license")).isEqualTo(1)
        assertThat(money.observeLicense().first()!!.licenseId).isEqualTo("lid-2")
        money.clearLicense()
        assertThat(money.license()).isNull()
        assertThat(money.observeLicense().first()).isNull()
    }

    @Test
    fun historyIsMostRecentFirstLimitedAndDeletable() = runBlocking {
        val history = handle.searchHistory
        history.add(SearchHistoryEntry(1_000, "facture", "facture|", 3, charged = true))
        history.add(SearchHistoryEntry(2_000, "train", "train|", 0, charged = false))
        history.add(SearchHistoryEntry(3_000, "pâtes", "pates|", 1, charged = true))
        assertThat(history.observeRecent(2).first().map { it.query }).containsExactly("pâtes", "train").inOrder()
        assertThat(history.deleteOlderThan(1_500)).isEqualTo(1)
        assertThat(history.observeRecent(10).first().map { it.query }).containsExactly("pâtes", "train").inOrder()
        history.clear()
        assertThat(history.observeRecent(10).first()).isEmpty()
    }

    @Test
    fun metricsIncrementPerDayAndKey() = runBlocking {
        val metrics = handle.metrics
        metrics.increment("2026-11-11", "captures_stored")
        metrics.increment("2026-11-12", "captures_stored")
        metrics.increment("2026-11-12", "captures_stored", by = 4)
        metrics.increment("2026-11-12", "drop_PASSWORD_FIELD")
        assertThat(metrics.day("2026-11-12")).containsExactly("captures_stored", 5L, "drop_PASSWORD_FIELD", 1L)
        assertThat(metrics.day("2026-11-10")).isEmpty()
        assertThat(metrics.range("2026-11-11", "2026-11-12").keys).containsExactly("2026-11-11", "2026-11-12").inOrder()
        assertThat(metrics.deleteBefore("2026-11-12")).isEqualTo(1)
        assertThat(metrics.range("2026-01-01", "2026-12-31").keys).containsExactly("2026-11-12")
    }
}
