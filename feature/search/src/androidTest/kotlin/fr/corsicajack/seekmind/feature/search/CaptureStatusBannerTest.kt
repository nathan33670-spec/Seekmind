package fr.corsicajack.seekmind.feature.search

import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.designsystem.theme.SeekMindTheme
import fr.corsicajack.seekmind.core.model.CaptureStatus
import java.time.ZoneId
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The capture status banner, one state after the other (EF-CAP-05, ticket M6-09). */
@RunWith(AndroidJUnit4::class)
class CaptureStatusBannerTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val actions = mutableListOf<StatusAction>()

    @Before
    fun setUp() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) compose.enableAccessibilityChecks()
    }

    private var clock = NOW

    private fun show(status: CaptureStatus?, canEnable: Boolean = false) = compose.setContent {
        SeekMindTheme(dynamicColor = false) {
            SearchScreen(
                state = SearchUiState(capture = status),
                zone = ZoneId.of("Europe/Paris"),
                now = { clock },
                onQueryChange = {},
                onSearch = {},
                onPeriod = {},
                onApp = {},
                onRemoveUnderstood = {},
                onRetry = {},
                onOpenResult = {},
                onOpenSource = {},
                onReset = {},
                onStatusAction = { actions += it },
                canEnableCapture = canEnable,
            )
        }
    }

    @Test
    fun aPauseOffersToResume() {
        show(CaptureStatus.Paused(until = null))
        compose.onNodeWithText(context.getString(R.string.search_status_paused)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.search_status_resume)).performClick()
        assertThat(actions).containsExactly(StatusAction.RESUME)
    }

    @Test
    fun aTimedPauseSaysUntilWhen() {
        show(CaptureStatus.Paused(until = NOW + 3_600_000))
        compose.onNodeWithText(context.getString(R.string.search_status_paused_until, ""), substring = true)
            .assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.search_status_resume)).assertIsDisplayed()
    }

    @Test
    fun aDisabledServiceOffersToReactivateAndTheHelp() {
        show(CaptureStatus.ServiceDisabled)
        compose.onNodeWithText(context.getString(R.string.search_status_disabled)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.search_status_reactivate)).performClick()
        compose.onNodeWithText(context.getString(R.string.search_status_help)).performClick()
        assertThat(actions).containsExactly(StatusAction.REACTIVATE, StatusAction.HELP).inOrder()
    }

    @Test
    fun missingConsentOffersTheDisclosureOnceItExists() {
        show(CaptureStatus.ConsentMissing, canEnable = true)
        compose.onNodeWithText(context.getString(R.string.search_status_consent)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.search_status_enable)).performClick()
        assertThat(actions).containsExactly(StatusAction.ENABLE)
    }

    @Test
    fun missingConsentWithoutDisclosureHasNoButton() {
        show(CaptureStatus.ConsentMissing, canEnable = false)
        compose.onNodeWithText(context.getString(R.string.search_status_consent)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.search_status_enable)).assertDoesNotExist()
    }

    @Test
    fun activeMemorisationHasNoBannerAndSaysWhenItLastRemembered() {
        show(CaptureStatus.Active(lastCaptureAt = NOW - 2 * 60_000))
        compose.onNodeWithTag(SEARCH_STATUS_TAG).assertDoesNotExist()
        val line = context.resources.getQuantityString(R.plurals.search_last_capture_minutes, 2, 2)
        compose.onNodeWithText(line).assertIsDisplayed()
    }

    @Test
    fun theLastCaptureLineFollowsTheTime() {
        show(CaptureStatus.Active(lastCaptureAt = NOW - 2 * 60_000))
        compose.onNodeWithText(context.resources.getQuantityString(R.plurals.search_last_capture_minutes, 2, 2))
            .assertIsDisplayed()
        clock = NOW + 60 * 60_000
        compose.mainClock.advanceTimeBy(LAST_CAPTURE_TICK_MS + 1)
        compose.onNodeWithText(context.resources.getQuantityString(R.plurals.search_last_capture_hours, 1, 1))
            .assertIsDisplayed()
    }

    @Test
    fun anUnknownStatusShowsNothing() {
        show(null)
        compose.onNodeWithTag(SEARCH_STATUS_TAG).assertDoesNotExist()
    }

    @Test
    fun nothingRememberedYetSaysNothing() {
        show(CaptureStatus.Active(lastCaptureAt = null))
        compose.onNodeWithTag(SEARCH_STATUS_TAG).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.search_last_capture_now)).assertDoesNotExist()
    }

    private companion object {
        const val NOW = 1_794_441_600_000L
    }
}
