package fr.corsicajack.seekmind.feature.paywall

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.corsicajack.seekmind.core.designsystem.theme.SeekMindTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The credits sheet (docs/specs/monetisation.md §6, ticket M8-04). */
@RunWith(AndroidJUnit4::class)
class CreditsSheetTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(button: AdButton) = compose.setContent {
        SeekMindTheme {
            CreditsSheetContent(CreditsSheetState(button), dailyFreeCredits = 5, onWatch = {}, onCode = {})
        }
    }

    @Test
    fun withoutAdsNoAdButtonIsComposed() {
        show(AdButton.ABSENT)
        compose.onNodeWithTag(CREDITS_AD_TAG).assertDoesNotExist()
        compose.onNodeWithTag(CREDITS_CODE_TAG).assertIsDisplayed()
    }

    @Test
    fun theAdButtonWaitsForThePreparedAd() {
        show(AdButton.LOADING)
        compose.onNodeWithTag(CREDITS_AD_TAG).assertIsNotEnabled()
    }

    @Test
    fun aPreparedAdCanBeWatched() {
        show(AdButton.READY)
        compose.onNodeWithTag(CREDITS_AD_TAG).assertIsEnabled()
    }

    @Test
    fun pastTheDailyCeilingTheButtonIsDisabled() {
        show(AdButton.CEILING)
        compose.onNodeWithTag(CREDITS_AD_TAG).assertIsNotEnabled()
    }
}
