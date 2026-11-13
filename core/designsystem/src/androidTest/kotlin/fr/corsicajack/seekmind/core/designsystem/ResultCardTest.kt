package fr.corsicajack.seekmind.core.designsystem

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.designsystem.component.ResultCard
import fr.corsicajack.seekmind.core.designsystem.component.ResultCardModel
import fr.corsicajack.seekmind.core.designsystem.theme.SeekMindTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ResultCardTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val model = ResultCardModel(
        appLabel = "Messagerie",
        timeLabel = "mardi 21:12",
        title = "Dîner de vendredi",
        snippet = "Rendez-vous au petit restaurant près de la gare.",
        highlights = listOf(21..30),
        fromImage = true,
        similarCount = 3,
        sourceActionLabel = "Ouvrir dans Messagerie",
    )

    private var cardClicks = 0
    private var sourceClicks = 0

    @Before
    fun setUp() = compose.enableA11yChecksWhereSupported()

    private fun show(model: ResultCardModel) = compose.setContent {
        SeekMindTheme(dynamicColor = false) {
            ResultCard(model = model, icon = null, onClick = { cardClicks++ }, onSourceAction = { sourceClicks++ })
        }
    }

    @Test
    fun showsEveryField() {
        show(model)

        listOf(model.appLabel, model.timeLabel, model.title!!, model.snippet, model.sourceActionLabel).forEach {
            compose.onNodeWithText(it, useUnmergedTree = true).assertIsDisplayed()
        }
        compose.onNodeWithText(context.getString(R.string.designsystem_result_from_image), useUnmergedTree = true)
            .assertIsDisplayed()
        compose.onNodeWithText(
            context.resources.getQuantityString(R.plurals.designsystem_result_similar, 3, 3),
            useUnmergedTree = true,
        ).assertIsDisplayed()
        // Initial of the application while its icon is not loaded.
        compose.onNodeWithText("M", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun optionalFieldsAreLeftOutWhenAbsent() {
        show(model.copy(title = null, fromImage = false, similarCount = 0))

        compose.onNodeWithText(model.snippet, useUnmergedTree = true).assertIsDisplayed()
        assertThat(compose.onAllNodesWithText(model.title!!)).isEmpty()
        assertThat(compose.onAllNodesWithText(context.getString(R.string.designsystem_result_from_image))).isEmpty()
    }

    @Test
    fun theCardOpensTheDetailAndTheButtonReturnsToTheSource() {
        show(model)

        compose.onNodeWithText(model.sourceActionLabel).performClick()
        assertThat(sourceClicks).isEqualTo(1)
        assertThat(cardClicks).isEqualTo(0)

        compose.onNodeWithText(model.snippet).performClick()
        assertThat(cardClicks).isEqualTo(1)
    }

    @Test
    fun theReturnToTheSourceIsACustomActionOfTheCard() {
        show(model)

        val card = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions))
        val actions = card.fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertThat(actions.map { it.label }).containsExactly(model.sourceActionLabel)

        compose.runOnIdle { actions.single().action() }
        assertThat(sourceClicks).isEqualTo(1)
    }

    private fun ComposeContentTestRule.onAllNodesWithText(text: String) =
        onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes()
}
