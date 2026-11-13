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
import fr.corsicajack.seekmind.core.model.MatchKind
import fr.corsicajack.seekmind.core.model.Relaxation
import fr.corsicajack.seekmind.core.model.SearchHit
import fr.corsicajack.seekmind.core.model.Snippet
import java.time.ZoneId
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The screen in each state of docs/specs/ui-ux.md §3.2 (ticket M6-03). */
@RunWith(AndroidJUnit4::class)
class SearchScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) compose.enableAccessibilityChecks()
    }

    private var searched: String? = null
    private var reset = false

    private fun show(state: SearchUiState) = compose.setContent {
        SeekMindTheme(dynamicColor = false) {
            SearchScreen(
                state = state,
                zone = ZoneId.of("Europe/Paris"),
                now = { NOW },
                onQueryChange = {},
                onSearch = { searched = it },
                onPeriod = {},
                onApp = {},
                onRemoveUnderstood = {},
                onOpenResult = {},
                onOpenSource = {},
                onReset = { reset = true },
            )
        }
    }

    @Test
    fun idleOffersTheRecentSearches() {
        show(SearchUiState(recent = listOf("tarte aux pommes")))
        compose.onNodeWithText("tarte aux pommes").performClick()
        assertThat(searched).isEqualTo("tarte aux pommes")
    }

    @Test
    fun keywordResultsWhileRefining() {
        show(SearchUiState(query = "tarte", phase = SearchPhase.RESULTS, refining = true, results = listOf(item(1))))
        compose.onNodeWithTag(SEARCH_REFINING_TAG).assertIsDisplayed()
        compose.onNodeWithTag(SEARCH_RESULTS_TAG).assertIsDisplayed()
        compose.onNodeWithText("Recette de la tarte", substring = true).assertIsDisplayed()
    }

    @Test
    fun relaxedResultsSayWhichFilterWasDropped() {
        show(
            SearchUiState(
                query = "tarte",
                phase = SearchPhase.RESULTS,
                results = listOf(item(1)),
                relaxed = Relaxation.TIME,
                relaxedLabel = "mardi soir",
            ),
        )
        compose.onNodeWithText(context.getString(R.string.search_relaxed_time_label, "mardi soir")).assertIsDisplayed()
    }

    @Test
    fun noResultSaysItWasNotCounted() {
        show(SearchUiState(query = "introuvable", phase = SearchPhase.NO_RESULT, notCharged = true))
        compose.onNodeWithText(context.getString(R.string.search_not_charged), substring = true).assertIsDisplayed()
    }

    @Test
    fun anUnavailableDatabaseOffersTheResetAfterConfirmation() {
        show(SearchUiState(query = "tarte", phase = SearchPhase.UNAVAILABLE))
        compose.onNodeWithText(context.getString(R.string.search_reset)).performClick()
        assertThat(reset).isFalse()
        compose.onNodeWithText(context.getString(R.string.search_reset_title)).assertIsDisplayed()
        compose.onAllNodesWithTextSafe(context.getString(R.string.search_reset)).last().performClick()
        assertThat(reset).isTrue()
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextSafe(text: String) =
        onAllNodes(androidx.compose.ui.test.hasText(text)).let { nodes ->
            List(nodes.fetchSemanticsNodes().size) { nodes[it] }
        }

    private companion object {
        const val NOW = 1_794_441_600_000L

        fun item(id: Long) = SearchResultItem(
            SearchHit(
                captureId = id,
                score = 1.0,
                packageName = "com.example.notes",
                windowTitle = "Recettes",
                url = null,
                firstSeenAt = NOW,
                lastSeenAt = NOW,
                snippet = Snippet("Recette de la tarte aux pommes", listOf(14..18)),
                textSource = 1,
                matchedBy = setOf(MatchKind.LEXICAL),
                similarCount = 0,
            ),
            appLabel = "Notes",
        )
    }
}
