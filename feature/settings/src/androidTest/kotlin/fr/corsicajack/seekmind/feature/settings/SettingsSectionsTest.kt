package fr.corsicajack.seekmind.feature.settings

import android.os.Build
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.designsystem.theme.SeekMindTheme
import fr.corsicajack.seekmind.core.model.AppFamily
import fr.corsicajack.seekmind.core.model.CaptureStatus
import fr.corsicajack.seekmind.core.model.Delivery
import fr.corsicajack.seekmind.core.model.ModelSpec
import fr.corsicajack.seekmind.core.model.PackState
import fr.corsicajack.seekmind.engine.privacy.AppStatus
import fr.corsicajack.seekmind.engine.privacy.AppStatusReason
import fr.corsicajack.seekmind.engine.privacy.apps.SensitiveApps
import java.time.ZoneId
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The sections of Settings (ticket M6-06, docs/specs/ui-ux.md §8): pause, family, exclusion, model, wipe. */
@RunWith(AndroidJUnit4::class)
class SettingsSectionsTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) compose.enableAccessibilityChecks()
    }

    @Test
    fun aPauseIsChosenInTheDialog() {
        val chosen = mutableListOf<PauseChoice>()
        compose.setContent {
            SeekMindTheme(dynamicColor = false) {
                MemorisationSettingsSection(
                    state = MemorisationUiState(CaptureStatus.Active(null), consented = true),
                    zone = ZoneId.of("UTC"),
                    onPause = { chosen += it },
                    onResume = {},
                    onActivate = {},
                    onReadDisclosure = {},
                    onWithdraw = {},
                    onDismissWipeOffer = {},
                    onWipe = {},
                )
            }
        }
        compose.onNodeWithTag(PAUSE_ROW_TAG).performClick()
        compose.onNodeWithText(context.getString(R.string.settings_pause_one_hour)).performClick()
        assertThat(chosen).containsExactly(PauseChoice.ONE_HOUR)
    }

    @Test
    fun aFamilySwitchReportsItsFamily() {
        val toggled = mutableListOf<AppFamily>()
        compose.setContent {
            SeekMindTheme(dynamicColor = false) {
                AppsSettingsSection(
                    enabledFamilies = setOf(AppFamily.BROWSERS, AppFamily.MESSAGING, AppFamily.OTHER),
                    onToggleFamily = { toggled += it },
                    onOpenApps = {},
                )
            }
        }
        compose.onNodeWithTag(familySwitchTag(AppFamily.SOCIAL_MEDIA)).assertIsOff()
        compose.onNodeWithTag(familySwitchTag(AppFamily.BROWSERS)).assertIsOn().performClick()
        assertThat(toggled).containsExactly(AppFamily.BROWSERS)
    }

    @Test
    fun anApplicationIsExcludedAndTheStrictListIsLocked() {
        val changes = mutableListOf<Pair<String, Boolean>>()
        compose.setContent {
            SeekMindTheme(dynamicColor = false) {
                AppsListScreen(
                    state = AppsUiState(
                        enabledFamilies = AppFamily.entries.toSet(),
                        apps = listOf(
                            AppRow(NOTES, AppStatus(AppStatusReason.FAMILY_ENABLED, AppFamily.OTHER, null)),
                            AppRow(VAULT, AppStatus(AppStatusReason.STRICT_LIST, null, SensitiveApps.Level.STRICT)),
                        ),
                        query = "",
                        pendingInclude = null,
                    ),
                    onQueryChange = {},
                    onSetRemembered = { app, on -> changes += app.packageName to on },
                    onConfirmInclude = {},
                    onCancelInclude = {},
                )
            }
        }
        compose.onNodeWithTag(appSwitchTag(VAULT.packageName)).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.settings_app_strict)).assertExists()
        compose.onNodeWithTag(appSwitchTag(NOTES.packageName)).assertIsOn().performClick()
        assertThat(changes).containsExactly(NOTES.packageName to false)
    }

    @Test
    fun includingASensitiveApplicationAsksFirst() {
        var confirmed = 0
        compose.setContent {
            SeekMindTheme(dynamicColor = false) {
                AppsListScreen(
                    state = AppsUiState(AppFamily.entries.toSet(), emptyList(), "", pendingInclude = NOTES),
                    onQueryChange = {},
                    onSetRemembered = { _, _ -> },
                    onConfirmInclude = { confirmed++ },
                    onCancelInclude = {},
                )
            }
        }
        compose.onNodeWithText(context.getString(R.string.settings_apps_include_confirm)).performClick()
        assertThat(confirmed).isEqualTo(1)
    }

    @Test
    fun changingTheModelIsConfirmedAndTheRebuildIsShown() {
        val used = mutableListOf<String>()
        var confirmed = 0
        compose.setContent {
            SeekMindTheme(dynamicColor = false) {
                LanguageSettingsSection(
                    state = LanguageUiState(
                        rows = listOf(
                            LanguageRow(FR, PackState.Installed, true),
                            LanguageRow(MULTI, PackState.Installed, false),
                        ),
                        importsFiles = false,
                        pendingFragments = 120,
                        confirming = MULTI,
                        error = null,
                    ),
                    onDownload = {},
                    onConfirmDownload = {},
                    onImport = { _, _ -> },
                    onUse = { used += it },
                    onConfirmUse = { confirmed++ },
                    onCancelUse = {},
                    onRemove = {},
                    onErrorShown = {},
                )
            }
        }
        compose.onNodeWithText(context.getString(R.string.settings_languages_use_text)).assertExists()
        compose.onNodeWithTag(REBUILD_TAG, useUnmergedTree = true).assertExists()
        val use = context.getString(R.string.settings_languages_use)
        // The confirm button of the dialog, not the « Utiliser » of the row below it.
        compose.onNode(hasText(use) and hasClickAction() and hasAnyAncestor(hasTestTag(CONFIRM_MODEL_TAG)))
            .performClick()
        assertThat(confirmed).isEqualTo(1)
        assertThat(used).isEmpty()
    }

    @Test
    fun eraseEverythingNeedsTheWord() {
        var wiped = 0
        compose.setContent {
            SeekMindTheme(dynamicColor = false) { WipeDialog(onConfirm = { wiped++ }, onDismiss = {}) }
        }
        compose.onNodeWithTag(WIPE_CONFIRM_TAG).assertIsNotEnabled()
        val word = context.getString(R.string.settings_wipe_word)
        compose.onNodeWithTag(WIPE_FIELD_TAG).performTextInput(word.dropLast(1))
        compose.onNodeWithTag(WIPE_CONFIRM_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(WIPE_FIELD_TAG).performTextInput(word.takeLast(1))
        compose.onNodeWithTag(WIPE_CONFIRM_TAG).assertIsEnabled().performClick()
        assertThat(wiped).isEqualTo(1)
    }

    private companion object {
        val NOTES = InstalledApp("com.example.notes", "Notes")
        val VAULT = InstalledApp("com.example.vault", "Vault")
        val FR = ModelSpec("fr", 1, setOf("fr"), 37_000_000, Delivery.BUNDLED, null)
        val MULTI = ModelSpec("multi", 2, emptySet(), 118_000_000, Delivery.PACK, "modelpack_multi")
    }
}
