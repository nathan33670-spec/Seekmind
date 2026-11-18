package fr.corsicajack.seekmind

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.database.store.CreditMovement
import fr.corsicajack.seekmind.core.database.store.CreditReason
import fr.corsicajack.seekmind.feature.paywall.CREDITS_SHEET_TAG
import fr.corsicajack.seekmind.feature.search.SEARCH_CREDITS_TAG
import fr.corsicajack.seekmind.link.LINK_CONFIRM_TAG
import fr.corsicajack.seekmind.link.LinkActivity
import fr.corsicajack.seekmind.navigation.TopLevelDestination
import fr.corsicajack.seekmind.navigation.screenTag
import fr.corsicajack.seekmind.ui.navItemTag
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end tests of the monetisation with the real bindings (ticket M8-08): the activation by link
 * through to the search screen, and an empty balance that leaves the rest of the application usable
 * (EF-MON-07).
 */
@RunWith(AndroidJUnit4::class)
class MonetizationE2ETest {
    @get:Rule(order = 0)
    val onboardingDone = OnboardingDoneRule()

    @get:Rule(order = 1)
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val store get() = CaptureEntryPointAccess.database(context).monetization

    @Before
    fun setUp() = runBlocking<Unit> { store.clearLicense() }

    @After
    fun tearDown() = runBlocking<Unit> { store.clearLicense() }

    private fun code(): String {
        val json = instrumentation.context.assets.open("vectors.json").bufferedReader().use { it.readText() }
        val vectors = JSONObject(json).getJSONArray("vectors")
        return (0 until vectors.length()).map(vectors::getJSONObject).single { it.getString("name") == "valid" }
            .getString("token")
    }

    @Test
    fun anActivationLinkActivatesTheSearch() {
        val link = Intent(Intent.ACTION_VIEW, Uri.parse("seekmind://activate?t=${code()}"))
            .setClass(context, LinkActivity::class.java)
        ActivityScenario.launch<LinkActivity>(link).use {
            compose.onNodeWithTag(LINK_CONFIRM_TAG).performClick()
            compose.waitUntil(TIMEOUT_MS) { runBlocking { store.license() } != null }
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            val activated = context.getString(fr.corsicajack.seekmind.core.designsystem.R.string.designsystem_activated)
            compose.waitUntil(TIMEOUT_MS) {
                compose.onAllNodes(hasTestTag(SEARCH_CREDITS_TAG)).fetchSemanticsNodes().any { node ->
                    node.config.toString().contains(activated)
                }
            }
            // Activated: no credits sheet.
            compose.onNodeWithTag(SEARCH_CREDITS_TAG).performClick()
            compose.waitForIdle()
            assertThat(compose.onAllNodes(hasTestTag(CREDITS_SHEET_TAG)).fetchSemanticsNodes()).isEmpty()
        }
    }

    @Test
    fun withoutCreditTheJournalAndTheSettingsStayOpen() = runBlocking<Unit> {
        val before = store.balance()
        store.append(CreditMovement(System.currentTimeMillis(), -before, CreditReason.SEARCH, "e2e"))
        try {
            assertThat(store.balance()).isEqualTo(0)
            ActivityScenario.launch(MainActivity::class.java).use {
                for (destination in listOf(TopLevelDestination.JOURNAL, TopLevelDestination.SETTINGS)) {
                    compose.onNodeWithTag(navItemTag(destination)).performClick()
                    compose.onNodeWithTag(screenTag(destination)).assertIsDisplayed()
                }
            }
        } finally {
            // The balance as it was, for the tests that follow in this process.
            store.append(CreditMovement(System.currentTimeMillis(), before, CreditReason.WELCOME, "e2e"))
        }
    }

    private companion object {
        const val TIMEOUT_MS = 15_000L
    }
}
