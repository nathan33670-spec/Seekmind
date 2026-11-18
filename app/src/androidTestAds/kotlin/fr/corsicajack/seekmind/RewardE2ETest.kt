package fr.corsicajack.seekmind

import android.view.KeyEvent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import fr.corsicajack.seekmind.core.database.store.CreditMovement
import fr.corsicajack.seekmind.core.database.store.CreditReason
import fr.corsicajack.seekmind.core.model.RewardOutcome
import fr.corsicajack.seekmind.core.model.RewardReadiness
import fr.corsicajack.seekmind.core.model.config.MonetizationConfig
import fr.corsicajack.seekmind.core.model.port.RewardProvider
import fr.corsicajack.seekmind.core.testing.FakeRewardProvider
import fr.corsicajack.seekmind.di.AdsModule
import fr.corsicajack.seekmind.engine.monetization.CreditLedger
import fr.corsicajack.seekmind.feature.paywall.CREDITS_AD_TAG
import fr.corsicajack.seekmind.feature.paywall.CREDITS_MESSAGE_TAG
import fr.corsicajack.seekmind.feature.paywall.CREDITS_SHEET_TAG
import fr.corsicajack.seekmind.feature.search.SEARCH_CREDITS_TAG
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * E2E-15 (docs/06-qualite-tests-ci.md §4, ticket M8-08): the rewarded ads through the whole application,
 * with a fake ad provider bound in place of AdMob. Run apart, with the Hilt test application
 * (-Pseekmind.hiltTests=true, device-tests.yml).
 */
@HiltAndroidTest
@UninstallModules(AdsModule::class)
class RewardE2ETest {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val onboardingDone = OnboardingDoneRule()

    @get:Rule(order = 2)
    val compose = createEmptyComposeRule()

    /** In place of AdMob: answers what each step of the test sets. */
    @BindValue
    @JvmField
    val provider: RewardProvider = FakeRewardProvider()

    private val ads get() = provider as FakeRewardProvider

    @BindValue
    @JvmField
    val config: MonetizationConfig = MonetizationConfig()

    @Inject lateinit var ledger: CreditLedger

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun setUp() = runBlocking<Unit> {
        hilt.inject()
        ledger.ensureWelcome()
    }

    @After
    fun tearDown() {
        if (::scenario.isInitialized) scenario.close()
    }

    private fun balance() = runBlocking { ledger.balance() }

    /** Brings the balance to [target] with a movement of the search kind. */
    private fun setBalance(target: Int) = runBlocking {
        val store = (CaptureEntryPointAccess.database(context)).monetization
        store.append(CreditMovement(System.currentTimeMillis(), target - ledger.balance(), CreditReason.SEARCH, "e2e"))
    }

    private fun openSheet() {
        compose.onNodeWithTag(SEARCH_CREDITS_TAG).performClick()
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodes(hasTestTag(CREDITS_AD_TAG) and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun closeSheet() {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodes(hasTestTag(CREDITS_SHEET_TAG)).fetchSemanticsNodes().isEmpty()
        }
    }

    private fun watch() = compose.onNodeWithTag(CREDITS_AD_TAG).performClick()

    @Test
    fun e2e15_aWatchedAdGivesFiveAClosedOneNothingAndNoAdOneCourtesySearch() {
        setBalance(10)
        scenario = ActivityScenario.launch(MainActivity::class.java)

        // Watched to the end: +5, the sheet closes.
        openSheet()
        watch()
        compose.waitUntil(TIMEOUT_MS) { balance() == 15 }
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodes(hasTestTag(CREDITS_SHEET_TAG)).fetchSemanticsNodes().isEmpty()
        }

        // Closed before the end: nothing, the sheet says why and stays.
        ads.outcome = RewardOutcome.DISMISSED
        openSheet()
        watch()
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodes(hasTestTag(CREDITS_MESSAGE_TAG)).fetchSemanticsNodes().isNotEmpty()
        }
        assertThat(balance()).isEqualTo(15)
        closeSheet()

        // No ad available: one courtesy search.
        ads.readiness = RewardReadiness.NO_FILL
        openSheet()
        watch()
        compose.waitUntil(TIMEOUT_MS) { balance() == 16 }
        assertThat(ads.shows).isEqualTo(2)
    }

    @Test
    fun aSearchWithoutCreditOpensTheSheetAndGoesOnAfterTheReward() {
        setBalance(0)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.onNode(hasSetTextAction()).performTextInput("zanzibar")
        compose.onNode(hasSetTextAction()).performImeAction()

        // No credit left: the sheet opens on its own.
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodes(hasTestTag(CREDITS_AD_TAG) and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        watch()

        // Credited: the sheet closes and the search goes on (nothing is found in an empty database).
        val noResult = context.getString(fr.corsicajack.seekmind.feature.search.R.string.search_no_result_title)
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodes(hasText(noResult)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText(noResult)).assertIsDisplayed()
        // A search without results is not charged.
        assertThat(balance()).isEqualTo(5)
    }

    private companion object {
        const val TIMEOUT_MS = 15_000L
    }
}
