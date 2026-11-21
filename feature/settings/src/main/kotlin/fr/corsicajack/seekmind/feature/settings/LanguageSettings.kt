package fr.corsicajack.seekmind.feature.settings

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import fr.corsicajack.seekmind.core.designsystem.component.ConfirmDialog
import fr.corsicajack.seekmind.core.designsystem.component.SettingRow
import fr.corsicajack.seekmind.core.designsystem.preview.SeekMindPreviews
import fr.corsicajack.seekmind.core.designsystem.theme.SeekMindTheme
import fr.corsicajack.seekmind.core.designsystem.theme.Spacing
import fr.corsicajack.seekmind.core.model.Delivery
import fr.corsicajack.seekmind.core.model.ModelSpec
import fr.corsicajack.seekmind.core.model.PackState
import kotlin.math.roundToInt

/** Test tag of the « Utiliser » button of the model [id]. */
fun useModelTag(id: String) = "settings_use_model_$id"

/** Test tag of the confirmation of a change of model. */
const val CONFIRM_MODEL_TAG = "settings_confirm_model"

/** Test tag of the progress of the rebuild. */
const val REBUILD_TAG = "settings_rebuild"

/**
 * « Langues de recherche » (docs/specs/ui-ux.md §3.5, docs/specs/embeddings.md §5): the active model, the
 * packs with their size, state and download progress; download, Google Play's confirmation, import of a
 * file, use (after a confirmation), deletion; the progress of the rebuild of the index by meaning.
 */
@Composable
fun LanguageSettingsSection(
    state: LanguageUiState,
    onDownload: (String) -> Unit,
    onConfirmDownload: () -> Unit,
    onImport: (String, String) -> Unit,
    onUse: (String) -> Unit,
    onConfirmUse: () -> Unit,
    onCancelUse: () -> Unit,
    onRemove: (String) -> Unit,
    onErrorShown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var importing by rememberSaveable { mutableStateOf<String?>(null) }
    var removing by rememberSaveable { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = importing
        importing = null
        if (uri != null && id != null) onImport(id, uri.toString())
    }
    Column(modifier = modifier.fillMaxWidth()) {
        SectionTitle(stringResource(R.string.settings_languages_title))
        Text(
            text = stringResource(R.string.settings_languages_intro),
            modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s),
        )
        state.rows.forEach { row ->
            SettingRow(title = modelLabel(row.spec), summary = rowSummary(row))
            if (row.state is PackState.Downloading) {
                LinearProgressIndicator(
                    progress = { row.state.progress },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l),
                )
            }
            Row(modifier = Modifier.padding(horizontal = Spacing.m)) {
                when {
                    row.state is PackState.Installed && !row.active -> TextButton(
                        onClick = { onUse(row.spec.id) },
                        modifier = Modifier.testTag(useModelTag(row.spec.id)),
                    ) { Text(text = stringResource(R.string.settings_languages_use)) }

                    row.state is PackState.AwaitingConfirmation -> TextButton(onClick = onConfirmDownload) {
                        Text(text = stringResource(R.string.settings_languages_confirm_download))
                    }

                    row.isPack && (row.state is PackState.NotInstalled || row.state is PackState.Failed) ->
                        if (state.importsFiles) {
                            TextButton(onClick = {
                                importing = row.spec.id
                                picker.launch(arrayOf("*/*"))
                            }) { Text(text = stringResource(R.string.settings_languages_import)) }
                        } else {
                            TextButton(onClick = { onDownload(row.spec.id) }) {
                                Text(text = stringResource(R.string.settings_languages_download))
                            }
                        }
                }
                if (row.isPack && row.state is PackState.Installed) {
                    TextButton(onClick = { removing = row.spec.id }) {
                        Text(text = stringResource(R.string.settings_languages_remove))
                    }
                }
            }
        }
        if (state.pendingFragments > 0) {
            SectionMessage(
                pluralStringResource(
                    R.plurals.settings_languages_rebuild,
                    state.pendingFragments,
                    state.pendingFragments,
                ),
                modifier = Modifier.testTag(REBUILD_TAG),
            )
        }
        state.error?.let { error ->
            SectionMessage(
                stringResource(
                    when (error) {
                        LanguageError.IMPORT_INVALID -> R.string.settings_languages_import_invalid
                        LanguageError.ACTIVATION_FAILED -> R.string.settings_languages_activation_failed
                        LanguageError.DOWNLOAD_FAILED -> R.string.settings_languages_download_failed
                    },
                ),
            )
        }
    }
    state.confirming?.let { spec ->
        ConfirmDialog(
            title = stringResource(R.string.settings_languages_use_title, modelLabel(spec)),
            text = stringResource(R.string.settings_languages_use_text),
            confirmLabel = stringResource(R.string.settings_languages_use),
            dismissLabel = stringResource(R.string.settings_cancel),
            onConfirm = {
                onErrorShown()
                onConfirmUse()
            },
            onDismiss = onCancelUse,
            modifier = Modifier.testTag(CONFIRM_MODEL_TAG),
        )
    }
    removing?.let { id ->
        val spec = state.rows.firstOrNull { it.spec.id == id }?.spec ?: return@let
        ConfirmDialog(
            title = stringResource(R.string.settings_languages_remove_title, modelLabel(spec)),
            text = stringResource(R.string.settings_languages_remove_text),
            confirmLabel = stringResource(R.string.settings_languages_remove),
            dismissLabel = stringResource(R.string.settings_cancel),
            onConfirm = {
                removing = null
                onRemove(id)
            },
            onDismiss = { removing = null },
            destructive = true,
        )
    }
}

@Composable
internal fun modelLabel(spec: ModelSpec): String = when (spec.id) {
    "fr" -> stringResource(R.string.settings_languages_model_fr)
    "multi" -> stringResource(R.string.settings_languages_model_multi)
    else -> spec.id
}

@Composable
private fun rowSummary(row: LanguageRow): String {
    val size = Formatter.formatShortFileSize(LocalContext.current, row.spec.sizeBytes)
    return when {
        row.active -> stringResource(R.string.settings_languages_active)

        row.spec.delivery == Delivery.BUNDLED -> stringResource(R.string.settings_languages_bundled)

        else -> when (val state = row.state) {
            PackState.NotInstalled -> stringResource(R.string.settings_languages_not_installed, size)

            is PackState.Downloading ->
                stringResource(R.string.settings_languages_downloading, (state.progress * PERCENT).roundToInt())

            PackState.AwaitingConfirmation -> stringResource(R.string.settings_languages_awaiting)

            PackState.Installed -> stringResource(R.string.settings_languages_installed, size)

            is PackState.Failed -> stringResource(R.string.settings_languages_failed)
        }
    }
}

private const val PERCENT = 100

@SeekMindPreviews
@Composable
private fun LanguageSettingsSectionPreview() {
    val fr = ModelSpec("fr", 1, setOf("fr"), 37_000_000, Delivery.BUNDLED, null)
    val multi = ModelSpec("multi", 2, emptySet(), 118_000_000, Delivery.PACK, "modelpack_multi")
    SeekMindTheme(dynamicColor = false) {
        LanguageSettingsSection(
            state = LanguageUiState(
                rows = listOf(
                    LanguageRow(fr, PackState.Installed, true),
                    LanguageRow(multi, PackState.Downloading(0.4f), false),
                ),
                importsFiles = false,
                pendingFragments = 0,
                confirming = null,
                error = null,
            ),
            onDownload = {},
            onConfirmDownload = {},
            onImport = { _, _ -> },
            onUse = {},
            onConfirmUse = {},
            onCancelUse = {},
            onRemove = {},
            onErrorShown = {},
        )
    }
}
