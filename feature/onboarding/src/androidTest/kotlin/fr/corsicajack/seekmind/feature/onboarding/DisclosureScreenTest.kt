package fr.corsicajack.seekmind.feature.onboarding

import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.designsystem.theme.SeekMindTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** docs/05-conformite-play-store.md §4.1: « J'accepte » is enabled once the whole text was shown. */
@RunWith(AndroidJUnit4::class)
class DisclosureScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun acceptIsEnabledOnlyOnceTheEndOfTheTextIsShown() {
        var accepted = 0
        var refused = 0
        compose.setContent {
            SeekMindTheme(dynamicColor = false) {
                OnboardingScreen(
                    state = OnboardingUiState(OnboardingStep.DISCLOSURE),
                    familyApps = emptyMap(),
                    onNextIntro = {},
                    onSkipIntro = {},
                    onAccept = { accepted++ },
                    onRefuse = { refused++ },
                    onToggleFamily = {},
                    onConfirmFamilies = {},
                    onOpenSettings = {},
                    onHelp = {},
                    onContinueAfterConfirmation = {},
                    onChooseLock = {},
                    onLeave = {},
                    // A short screen, so that the text has to be scrolled.
                    modifier = Modifier.height(320.dp),
                )
            }
        }
        compose.onNodeWithTag(DISCLOSURE_ACCEPT_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(DISCLOSURE_REFUSE_TAG).assertIsEnabled()
        repeat(10) { compose.onNodeWithTag(DISCLOSURE_TEXT_TAG).performTouchInput { swipeUp() } }
        compose.onNodeWithTag(DISCLOSURE_ACCEPT_TAG).assertIsEnabled().performClick()
        assertThat(accepted).isEqualTo(1)
        compose.onNodeWithTag(DISCLOSURE_REFUSE_TAG).performClick()
        assertThat(refused).isEqualTo(1)
    }
}
