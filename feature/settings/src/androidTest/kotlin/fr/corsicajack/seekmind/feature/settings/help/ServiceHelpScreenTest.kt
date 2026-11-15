package fr.corsicajack.seekmind.feature.settings.help

import android.os.Build
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.corsicajack.seekmind.core.designsystem.theme.SeekMindTheme
import fr.corsicajack.seekmind.feature.settings.R
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** « Le service s'arrête tout seul » (ticket M6-09): every manufacturer of the ticket, as headings. */
@RunWith(AndroidJUnit4::class)
class ServiceHelpScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) compose.enableAccessibilityChecks()
        compose.setContent { SeekMindTheme(dynamicColor = false) { ServiceHelpScreen() } }
    }

    @Test
    fun theTitleAndTheExplanation() {
        compose.onNode(hasText(context.getString(R.string.settings_help_title)) and isHeading).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.settings_help_intro)).assertIsDisplayed()
    }

    @Test
    fun everyManufacturerHasItsStepsUnderAHeading() {
        for (manufacturer in SERVICE_HELP_MANUFACTURERS) {
            compose.onNode(hasText(context.getString(manufacturer.name)) and isHeading)
                .performScrollTo()
                .assertIsDisplayed()
            compose.onNodeWithText(context.getString(manufacturer.steps)).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithText(context.getString(R.string.settings_help_versions)).performScrollTo().assertIsDisplayed()
    }

    private companion object {
        val isHeading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
    }
}
