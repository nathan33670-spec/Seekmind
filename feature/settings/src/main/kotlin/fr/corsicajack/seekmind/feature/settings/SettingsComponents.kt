package fr.corsicajack.seekmind.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import fr.corsicajack.seekmind.core.designsystem.theme.Spacing

/** Test tag of the confirmation field of « Tout effacer ». */
const val WIPE_FIELD_TAG = "settings_wipe_field"

/** Test tag of the confirm button of « Tout effacer ». */
const val WIPE_CONFIRM_TAG = "settings_wipe_confirm"

/** The title of a section of the settings, announced as a heading. */
@Composable
internal fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(horizontal = Spacing.l, vertical = Spacing.s).semantics { heading() },
    )
}

/** A short message after an action, read by TalkBack when it appears. */
@Composable
internal fun SectionMessage(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.l, vertical = Spacing.s)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** A choice among [choices], one radio row each; choosing closes the dialog. */
@Composable
internal fun <T> ChoiceDialog(
    title: String,
    choices: List<T>,
    selected: T?,
    label: @Composable (T) -> String,
    onChoose: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = {
            Column(modifier = Modifier.selectableGroup()) {
                choices.forEach { choice ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = Spacing.minTouchTarget)
                            .selectable(
                                selected = choice == selected,
                                role = Role.RadioButton,
                                onClick = { onChoose(choice) },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = choice == selected, onClick = null)
                        Text(text = label(choice), modifier = Modifier.padding(start = Spacing.m))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(text = stringResource(R.string.settings_cancel)) } },
    )
}

/**
 * « Tout effacer » (docs/specs/ui-ux.md §3.5): the button stays inactive until the user types the word
 * of `settings_wipe_word` (« EFFACER »); then [onConfirm] erases everything and closes the application.
 */
@Composable
fun WipeDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    var typed by rememberSaveable { mutableStateOf("") }
    val word = stringResource(R.string.settings_wipe_word)
    val matches = typed.trim() == word
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.settings_wipe_title)) },
        text = {
            Column {
                Text(text = stringResource(R.string.settings_wipe_text, word))
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    singleLine = true,
                    label = { Text(text = stringResource(R.string.settings_wipe_field, word)) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.m).testTag(WIPE_FIELD_TAG),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = matches,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.testTag(WIPE_CONFIRM_TAG),
            ) { Text(text = stringResource(R.string.settings_wipe_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(text = stringResource(R.string.settings_cancel)) } },
    )
}
