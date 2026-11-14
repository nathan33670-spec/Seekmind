package fr.corsicajack.seekmind.feature.search.detail

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.designsystem.theme.SeekMindTheme
import fr.corsicajack.seekmind.core.model.Capture
import fr.corsicajack.seekmind.core.model.TextSource
import fr.corsicajack.seekmind.feature.search.R
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The detail of a capture (docs/specs/ui-ux.md §3.3, ticket M6-04). */
@RunWith(AndroidJUnit4::class)
class CaptureDetailScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private var openedSource: Capture? = null
    private var openedLink: String? = null
    private var deleted = false

    @Before
    fun setUp() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) compose.enableAccessibilityChecks()
    }

    private fun show(state: CaptureDetailState) = compose.setContent {
        SeekMindTheme(dynamicColor = false) {
            CaptureDetailScreen(
                state = state,
                zone = ZoneId.of("Europe/Paris"),
                onOpenSource = { openedSource = it },
                onOpenLink = { openedLink = it },
                onDelete = { deleted = true },
            )
        }
    }

    private fun loaded(capture: Capture = CAPTURE) = CaptureDetailState(
        loading = false,
        capture = capture,
        appLabel = "Notes",
        highlights = TextMarks.highlights(capture.text, listOf("tarte")),
        links = TextMarks.links(capture.text),
    )

    @Test
    fun theWholeTextAndItsMetadata() {
        show(loaded(CAPTURE.copy(textSource = TextSource.OCR)))
        compose.onNodeWithText("Recettes").assertIsDisplayed()
        compose.onNodeWithTag(DETAIL_TEXT_TAG).assertIsDisplayed()
        compose.onNodeWithText(CAPTURE.text).assertIsDisplayed()
        compose.onNodeWithText(context.resources.getQuantityString(R.plurals.detail_views, 3, 3)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.detail_from_image)).assertIsDisplayed()
    }

    @Test
    fun returnToTheSourceAndOpenALink() {
        show(loaded())
        compose.onNodeWithText(context.getString(R.string.search_open_app, "Notes")).performClick()
        assertThat(openedSource?.id).isEqualTo(CAPTURE.id)
        compose.onNodeWithText(LINK).performScrollTo().performClick()
        assertThat(openedLink).isEqualTo(LINK)
    }

    @Test
    fun deletionAsksForConfirmation() {
        show(loaded())
        val delete = context.getString(R.string.detail_delete)
        compose.onNodeWithText(delete).performClick()
        assertThat(deleted).isFalse()
        compose.onNodeWithText(context.getString(R.string.detail_delete_title)).assertIsDisplayed()
        val buttons = compose.onAllNodes(hasText(delete))
        buttons[buttons.fetchSemanticsNodes().size - 1].performClick()
        assertThat(deleted).isTrue()
    }

    @Test
    fun aDeletedCaptureSaysSo() {
        show(CaptureDetailState(loading = false, missing = true))
        compose.onNodeWithText(context.getString(R.string.detail_missing_title)).assertIsDisplayed()
    }

    @Test
    fun theCopyButtonMarksTheClipSensitive() {
        show(loaded())
        compose.onNodeWithText(context.getString(R.string.detail_copy)).performClick()
        compose.waitForIdle()
        var clip: ClipData? = null
        instrumentation.runOnMainSync {
            clip = context.getSystemService(ClipboardManager::class.java).primaryClip
        }
        assertThat(clip?.getItemAt(0)?.text?.toString()).isEqualTo(CAPTURE.text)
        assertThat(clip?.description?.extras?.getBoolean(IS_SENSITIVE)).isTrue()
    }

    @Test
    fun aFailedDeletionSaysSo() {
        show(loaded().copy(deleteFailed = true))
        compose.onNodeWithText(context.getString(R.string.detail_delete_failed)).assertIsDisplayed()
    }

    /** The old text toolbar path, the one a test can stand in for (the new context menu is a popup). */
    @OptIn(ExperimentalFoundationApi::class)
    @Test
    fun copyingTheSelectionMarksTheClipSensitive() {
        val newContextMenu = ComposeFoundationFlags.isNewContextMenuEnabled
        ComposeFoundationFlags.isNewContextMenuEnabled = false
        try {
            instrumentation.runOnMainSync { context.getSystemService(ClipboardManager::class.java).clearPrimaryClip() }
            val toolbar = CopyingToolbar()
            compose.setContent {
                SeekMindTheme(dynamicColor = false) {
                    CompositionLocalProvider(LocalTextToolbar provides toolbar) {
                        CaptureDetailScreen(
                            state = loaded(),
                            zone = ZoneId.of("Europe/Paris"),
                            onOpenSource = {},
                            onOpenLink = {},
                            onDelete = {},
                        )
                    }
                }
            }
            compose.onNodeWithTag(DETAIL_TEXT_TAG).performTouchInput { longClick(centerLeft + Offset(8f, 0f)) }
            compose.waitUntil(TIMEOUT_MS) { toolbar.copy != null }
            compose.runOnIdle { toolbar.copy!!.invoke() }
            var clip: ClipData? = null
            compose.waitUntil(TIMEOUT_MS) {
                instrumentation.runOnMainSync {
                    clip = context.getSystemService(ClipboardManager::class.java).primaryClip
                }
                clip?.getItemAt(0)?.text?.isNotEmpty() == true
            }
            assertThat(CAPTURE.text).contains(clip!!.getItemAt(0).text.toString())
            assertThat(clip!!.description.extras?.getBoolean(IS_SENSITIVE)).isTrue()
        } finally {
            ComposeFoundationFlags.isNewContextMenuEnabled = newContextMenu
        }
    }

    /** Stands for the system text toolbar: keeps the copy action that the selection offers. */
    private class CopyingToolbar : TextToolbar {
        @Volatile var copy: (() -> Unit)? = null

        override val status: TextToolbarStatus
            get() = if (copy != null) TextToolbarStatus.Shown else TextToolbarStatus.Hidden

        override fun showMenu(
            rect: Rect,
            onCopyRequested: (() -> Unit)?,
            onPasteRequested: (() -> Unit)?,
            onCutRequested: (() -> Unit)?,
            onSelectAllRequested: (() -> Unit)?,
        ) {
            if (onCopyRequested != null) copy = onCopyRequested
        }

        override fun hide() = Unit
    }

    @Test
    fun aSensitiveClipCarriesTheFlag() {
        val clip = sensitiveClip("label", "texte")
        assertThat(clip.description.extras.getBoolean(IS_SENSITIVE)).isTrue()
        assertThat(clip.getItemAt(0).text.toString()).isEqualTo("texte")
    }

    @Test
    fun theSelectionClipboardMarksEveryCopy() = runTest {
        val received = mutableListOf<ClipEntry?>()
        val delegate =
            object : Clipboard {
                override suspend fun getClipEntry(): ClipEntry? = received.lastOrNull()

                override suspend fun setClipEntry(clipEntry: ClipEntry?) {
                    received += clipEntry
                }
            }
        val clipboard = SensitiveClipboard(delegate)
        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("label", "sélection")))
        clipboard.setClipEntry(null)
        val entry = received.first()
        assertThat(entry?.clipData?.description?.extras?.getBoolean(IS_SENSITIVE)).isTrue()
        assertThat(entry?.clipData?.getItemAt(0)?.text?.toString()).isEqualTo("sélection")
        assertThat(received[1]).isNull()
        assertThat(clipboard.getClipEntry()).isNull()
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val NOW = 1_794_441_600_000L
        const val LINK = "https://exemple.fr/tarte"

        val CAPTURE = Capture(
            id = 7,
            firstSeenAt = NOW - 3_600_000,
            lastSeenAt = NOW,
            seenCount = 3,
            packageName = "com.example.notes",
            activityName = null,
            windowTitle = "Recettes",
            url = null,
            text = "Recette de la tarte aux pommes\nVoir $LINK",
            textSource = TextSource.TREE,
            charCount = 50,
            simhash = 0,
        )
    }
}
