package fr.corsicajack.seekmind

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.EntryPointAccessors
import fr.corsicajack.seekmind.core.database.DatabaseState
import fr.corsicajack.seekmind.di.CaptureEntryPoint
import fr.corsicajack.seekmind.link.LINK_CONFIRM_TAG
import fr.corsicajack.seekmind.link.LINK_MESSAGE_TAG
import fr.corsicajack.seekmind.link.LinkActivity
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The activation link (docs/specs/ui-ux.md §3.7, ticket M8-05), with a code of the test key `l-test`
 * that the debug build trusts: nothing happens before the confirmation, and a malformed link is neutral.
 */
@RunWith(AndroidJUnit4::class)
class LinkActivityTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val graph = EntryPointAccessors.fromApplication(context, CaptureEntryPoint::class.java)
    private val store get() = (graph.database() as DatabaseState.Ready).database.monetization

    private fun vector(name: String): String {
        val json = instrumentation.context.assets.open("vectors.json").bufferedReader().use { it.readText() }
        val vectors = JSONObject(json).getJSONArray("vectors")
        return (0 until vectors.length()).map(vectors::getJSONObject).single { it.getString("name") == name }
            .getString("token")
    }

    private fun launch(link: String) = ActivityScenario.launch<LinkActivity>(
        Intent(Intent.ACTION_VIEW, Uri.parse(link)).setClass(context, LinkActivity::class.java),
    )

    @Before
    fun setUp() = runBlocking<Unit> { store.clearLicense() }

    @After
    fun tearDown() = runBlocking<Unit> { store.clearLicense() }

    @Test
    fun theCodeOfALinkIsActivatedOnlyOnceConfirmed() = runBlocking<Unit> {
        launch("seekmind://activate?t=${vector("valid")}").use {
            compose.onNodeWithTag(LINK_CONFIRM_TAG).assertIsDisplayed()
            assertThat(store.license()).isNull()
            compose.onNodeWithTag(LINK_CONFIRM_TAG).performClick()
            compose.waitUntil(TIMEOUT_MS) { runBlocking { store.license() } != null }
            // The row is written before the screen shows the result: wait for it too.
            compose.waitUntil(TIMEOUT_MS) {
                compose.onAllNodes(
                    androidx.compose.ui.test.hasTestTag(LINK_MESSAGE_TAG),
                ).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag(LINK_MESSAGE_TAG).assertIsDisplayed()
            assertThat(store.license()?.licenseId).isEqualTo("TEST-0001")
        }
    }

    @Test
    fun aMalformedLinkIsNeutralAndChangesNothing() = runBlocking<Unit> {
        launch("seekmind://activate?t=" + "x".repeat(5_000)).use {
            compose.onNodeWithTag(LINK_MESSAGE_TAG).assertIsDisplayed()
            assertThat(compose.onAllNodesWithTagCount(LINK_CONFIRM_TAG)).isEqualTo(0)
            assertThat(store.license()).isNull()
        }
    }

    /** Ticket M10-05: links of another shape are neutral, without a confirmation to give. */
    @Test
    fun linksOfAnotherShapeAreNeutral() = runBlocking<Unit> {
        val code = vector("valid")
        for (link in listOf(
            "seekmind://activate",
            "seekmind://activate?t=",
            "seekmind://activate?x=$code",
            "seekmind://other?t=$code",
            "seekmind://activate?t=$code&t=$code",
            "seekmind://activate?t=%00$code",
            "seekmind://activate?t=" + "\u202e".repeat(20),
        )) {
            launch(link).use {
                compose.waitUntil(TIMEOUT_MS) {
                    compose.onAllNodes(androidx.compose.ui.test.hasTestTag(LINK_MESSAGE_TAG))
                        .fetchSemanticsNodes().isNotEmpty()
                }
                assertThat(compose.onAllNodesWithTagCount(LINK_CONFIRM_TAG)).isEqualTo(0)
            }
        }
        assertThat(store.license()).isNull()
    }

    /** Ticket M10-05: well-formed codes that do not verify are refused once confirmed, and change nothing. */
    @Test
    fun codesThatDoNotVerifyAreRefused() = runBlocking<Unit> {
        for (name in listOf("altered_signature", "altered_payload", "alg_none_signed", "unknown_kid")) {
            launch("seekmind://activate?t=${vector(name)}").use {
                compose.onNodeWithTag(LINK_CONFIRM_TAG).performClick()
                compose.waitUntil(TIMEOUT_MS) {
                    compose.onAllNodes(androidx.compose.ui.test.hasTestTag(LINK_MESSAGE_TAG))
                        .fetchSemanticsNodes().isNotEmpty()
                }
            }
            assertThat(store.license()).isNull()
        }
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithTagCount(tag: String) =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
