package fr.corsicajack.seekmind

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.CaptureHarness.Companion.FIXTURE
import fr.corsicajack.seekmind.core.database.store.CreditMovement
import fr.corsicajack.seekmind.core.database.store.CreditReason
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * E2E-14 (docs/06-qualite-tests-ci.md §4.2, ticket M2-08), the full path: a screen of the fixture is
 * captured by the service, found by the search screen, and its return to the source, built by the shipped
 * rule of the fixture (docs/specs/retour-source.md §4), opens DeepLinkActivity on the right identifier.
 * The screen opened is read back by the service: no other UiAutomation is needed.
 */
@RunWith(AndroidJUnit4::class)
class SourceReturnE2ETest {
    // The harness comes first: its UiAutomation must not suppress the accessibility services.
    @get:Rule(order = 0)
    val harness = CaptureHarness()

    @get:Rule(order = 1)
    val onboardingDone = OnboardingDoneRule()

    @get:Rule(order = 2)
    val compose = createEmptyComposeRule()

    @Test
    fun e2e14_theReturnToTheSourceOpensDeepLinkActivityOnTheRightIdentifier() = runBlocking<Unit> {
        harness.open("DeepLinkActivity")
        harness.awaitCapture(MARKER)
        // A fixture task left behind would be brought back as it is, without the link.
        harness.shell("am force-stop $FIXTURE")

        // One credit for the search, and the balance as it was afterwards, for the tests that follow.
        val store = harness.database().monetization
        val before = store.balance()
        if (before <
            1
        ) {
            store.append(CreditMovement(System.currentTimeMillis(), 1 - before, CreditReason.WELCOME, "e2e"))
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.onNode(hasSetTextAction()).performTextInput(QUERY)
                compose.onNode(hasSetTextAction()).performImeAction()
                val label = harness.context.getString(
                    fr.corsicajack.seekmind.feature.search.R.string.search_open_in,
                    FIXTURE_LABEL,
                )
                val button = hasText(label) and hasClickAction()
                compose.waitUntil(CaptureHarness.TIMEOUT_MS) {
                    compose.onAllNodes(button).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onAllNodes(button)[0].performClick()

                val (_, opened) = harness.awaitCapture("Fixture item item42")
                assertThat(opened.packageName).isEqualTo(FIXTURE)
            }
        } finally {
            val delta = before - store.balance()
            if (delta != 0) store.append(CreditMovement(System.currentTimeMillis(), delta, CreditReason.WELCOME, "e2e"))
        }
    }

    private companion object {
        const val MARKER = "SEEKMIND_FIXTURE_ITEM"
        const val QUERY = "kumquats"
        const val FIXTURE_LABEL = "SeekMind test fixtures"
    }
}
