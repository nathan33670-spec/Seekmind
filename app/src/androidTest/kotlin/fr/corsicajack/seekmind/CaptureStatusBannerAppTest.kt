package fr.corsicajack.seekmind

import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.corsicajack.seekmind.core.database.settings.Consent
import fr.corsicajack.seekmind.feature.search.R as SearchR
import fr.corsicajack.seekmind.feature.search.SEARCH_STATUS_TAG
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * EF-CAP-05 with the real status (ticket M6-09): once consent is given, a service turned off in the
 * system settings shows « Réactiver », also after the user comes back to the application.
 */
@RunWith(AndroidJUnit4::class)
class CaptureStatusBannerAppTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val service = "${context.packageName}/fr.corsicajack.seekmind.engine.capture.SeekMindAccessibilityService"
    private val reactivate get() = context.getString(SearchR.string.search_status_reactivate)

    @After
    fun tearDown() {
        setServiceEnabled(false)
        compose.activityRule.scenario.onActivity { activity -> runBlocking { activity.settings.reset() } }
    }

    @Test
    fun aServiceTurnedOffInTheSystemSettingsAsksToReactivateOnReturn() {
        setServiceEnabled(false)
        compose.activityRule.scenario.onActivity { activity ->
            runBlocking {
                activity.settings.acceptConsent(Consent(version = 1, acceptedAt = System.currentTimeMillis()))
            }
        }
        waitFor(present = true)

        // Turned on: memorisation is active, the banner goes.
        setServiceEnabled(true)
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodes(hasText(reactivate)).fetchSemanticsNodes().isEmpty()
        }

        // Turned off while SeekMind is in the background, then the user comes back.
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        setServiceEnabled(false)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        waitFor(present = true)
        compose.onNodeWithTag(SEARCH_STATUS_TAG).assertIsDisplayed()
    }

    private fun waitFor(present: Boolean) = compose.waitUntil(TIMEOUT_MS) {
        compose.onAllNodes(hasText(reactivate)).fetchSemanticsNodes().isNotEmpty() == present
    }

    private fun setServiceEnabled(enabled: Boolean) {
        if (enabled) {
            shell("settings put secure enabled_accessibility_services $service")
            shell("settings put secure accessibility_enabled 1")
        } else {
            shell("settings delete secure enabled_accessibility_services")
            shell("settings put secure accessibility_enabled 0")
        }
    }

    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use {
            it.readBytes()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 15_000L
    }
}
