package fr.corsicajack.seekmind

import android.os.Build
import android.view.WindowManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.navigation.TopLevelDestination
import fr.corsicajack.seekmind.navigation.TopLevelDestination.JOURNAL
import fr.corsicajack.seekmind.navigation.TopLevelDestination.SEARCH
import fr.corsicajack.seekmind.navigation.TopLevelDestination.SETTINGS
import fr.corsicajack.seekmind.navigation.screenTag
import fr.corsicajack.seekmind.ui.navItemTag
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Navigation of [MainActivity] between the three destinations of the bottom bar (ticket M6-01). */
@RunWith(AndroidJUnit4::class)
class NavigationTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Before
    fun setUp() {
        // Automatic accessibility checks; Compose offers them from Android 14.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) compose.enableAccessibilityChecks()
    }

    private fun assertShown(destination: TopLevelDestination) {
        compose.onNodeWithTag(screenTag(destination)).assertIsDisplayed()
        compose.onNodeWithTag(navItemTag(destination)).assertIsSelected()
        TopLevelDestination.entries.filter { it != destination }.forEach {
            compose.onNodeWithTag(screenTag(it)).assertDoesNotExist()
        }
    }

    private fun open(destination: TopLevelDestination) = compose.onNodeWithTag(navItemTag(destination)).performClick()

    private fun back() {
        // The back callback of the NavHost is enabled by a recomposition after the last navigation:
        // dispatching before it settles would reach the default handler, which finishes the activity.
        compose.waitForIdle()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    @Test
    fun startsOnSearch() = assertShown(SEARCH)

    @Test
    fun movesBetweenTheThreeDestinations() {
        open(JOURNAL)
        assertShown(JOURNAL)
        open(SETTINGS)
        assertShown(SETTINGS)
        open(SEARCH)
        assertShown(SEARCH)
        open(SETTINGS)
        assertShown(SETTINGS)
    }

    @Test
    fun backFromADestinationReturnsToSearch() {
        open(JOURNAL)
        back()
        assertShown(SEARCH)

        // Only the start destination stays below a destination of the bottom bar.
        open(JOURNAL)
        open(SETTINGS)
        back()
        assertShown(SEARCH)
    }

    @Test
    fun reselectingTheCurrentDestinationKeepsASingleEntry() {
        open(SETTINGS)
        open(SETTINGS)
        back()
        assertShown(SEARCH)
    }

    /** The content given by [MainActivity] reaches each screen: none of them is left empty. */
    @Test
    fun eachDestinationShowsItsContent() {
        for (destination in listOf(JOURNAL, SETTINGS, SEARCH)) {
            open(destination)
            compose.waitForIdle()
            val content = compose.onAllNodes(hasAnyAncestor(hasTestTag(screenTag(destination)))).fetchSemanticsNodes()
            assertThat(content).isNotEmpty()
        }
    }

    @Test
    fun theWindowIsSecure() {
        compose.activityRule.scenario.onActivity {
            assertThat(it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
                .isEqualTo(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
