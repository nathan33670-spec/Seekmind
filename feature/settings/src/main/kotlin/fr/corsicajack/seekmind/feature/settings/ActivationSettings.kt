package fr.corsicajack.seekmind.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import fr.corsicajack.seekmind.core.designsystem.component.ConfirmDialog
import fr.corsicajack.seekmind.core.designsystem.component.SettingRow
import fr.corsicajack.seekmind.core.designsystem.preview.SeekMindPreviews
import fr.corsicajack.seekmind.core.designsystem.theme.SeekMindTheme
import fr.corsicajack.seekmind.core.designsystem.theme.Spacing
import fr.corsicajack.seekmind.core.model.Entitlement
import fr.corsicajack.seekmind.engine.monetization.license.ActivationRefusal

const val ACTIVATION_INPUT_TAG = "settings_activation_input"
const val ACTIVATION_PASTE_TAG = "settings_activation_paste"
const val ACTIVATION_ACTIVATE_TAG = "settings_activation_activate"
const val ACTIVATION_REMOVE_TAG = "settings_activation_remove"
const val ACTIVATION_MESSAGE_TAG = "settings_activation_message"

/**
 * « Activation » section of the settings (docs/specs/ui-ux.md §3.5): the balance or the licence; with
 * the free plan, a field for a code and « Coller »; once activated, « Retirer l'activation ».
 * [onPaste] reads the clipboard, on the user's tap only.
 */
@Composable
fun ActivationSettingsSection(
    state: ActivationUiState,
    onInputChange: (String) -> Unit,
    onPaste: () -> Unit,
    onActivate: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmingRemoval by rememberSaveable { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.settings_activation_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s).semantics { heading() },
        )
        when (val entitlement = state.entitlement) {
            is Entitlement.Activated -> {
                SettingRow(
                    title = stringResource(R.string.settings_activation_activated),
                    summary = stringResource(R.string.settings_activation_license, entitlement.licenseId),
                )
                OutlinedButton(
                    onClick = { confirmingRemoval = true },
                    enabled = !state.busy,
                    modifier = Modifier.padding(horizontal = Spacing.l).testTag(ACTIVATION_REMOVE_TAG),
                ) { Text(text = stringResource(R.string.settings_activation_remove)) }
            }

            is Entitlement.Free -> {
                SettingRow(
                    title = stringResource(R.string.settings_activation_free),
                    summary = pluralStringResource(
                        R.plurals.settings_activation_balance,
                        entitlement.balance,
                        entitlement.balance,
                    ),
                )
                OutlinedTextField(
                    value = state.input,
                    onValueChange = onInputChange,
                    label = { Text(text = stringResource(R.string.settings_activation_code)) },
                    maxLines = 3,
                    // A code is not a word: no correction, no suggestion, nothing learnt by the keyboard.
                    keyboardOptions = KeyboardOptions(
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l).testTag(ACTIVATION_INPUT_TAG),
                )
                Row(
                    modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.m),
                ) {
                    OutlinedButton(
                        onClick = onPaste,
                        enabled = !state.busy,
                        modifier = Modifier.testTag(ACTIVATION_PASTE_TAG),
                    ) { Text(text = stringResource(R.string.settings_activation_paste)) }
                    Button(
                        onClick = onActivate,
                        enabled = !state.busy && state.input.isNotBlank(),
                        modifier = Modifier.testTag(ACTIVATION_ACTIVATE_TAG),
                    ) { Text(text = stringResource(R.string.settings_activation_activate)) }
                }
            }
        }
        state.message?.let { message ->
            Text(
                text = activationMessageText(message),
                style = MaterialTheme.typography.bodyMedium,
                color = if (message is ActivationMessage.Refused) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier
                    .padding(horizontal = Spacing.l, vertical = Spacing.s)
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .testTag(ACTIVATION_MESSAGE_TAG),
            )
        }
    }
    if (confirmingRemoval) {
        ConfirmDialog(
            title = stringResource(R.string.settings_activation_remove_title),
            text = stringResource(R.string.settings_activation_remove_text),
            confirmLabel = stringResource(R.string.settings_activation_remove),
            dismissLabel = stringResource(R.string.settings_cancel),
            onConfirm = {
                confirmingRemoval = false
                onRemove()
            },
            onDismiss = { confirmingRemoval = false },
            destructive = true,
        )
    }
}

@Composable
private fun activationMessageText(message: ActivationMessage): String = when (message) {
    ActivationMessage.Activated -> stringResource(R.string.settings_activation_done)
    ActivationMessage.Removed -> stringResource(R.string.settings_activation_removed)
    is ActivationMessage.Refused -> activationRefusalText(message.reason)
}

/** The precise message of a refused code (docs/specs/monetisation.md §5.4), shared with `LinkActivity`. */
@Composable
fun activationRefusalText(reason: ActivationRefusal): String = stringResource(
    when (reason) {
        ActivationRefusal.UNREADABLE -> R.string.settings_activation_unreadable
        ActivationRefusal.INVALID_SIGNATURE -> R.string.settings_activation_invalid
        ActivationRefusal.EXPIRED -> R.string.settings_activation_expired
        ActivationRefusal.REVOKED -> R.string.settings_activation_revoked
    },
)

@SeekMindPreviews
@Composable
private fun ActivationSettingsSectionPreview() {
    SeekMindTheme(dynamicColor = false) {
        ActivationSettingsSection(
            state = ActivationUiState(Entitlement.Free(7), input = "", busy = false, message = null),
            onInputChange = {},
            onPaste = {},
            onActivate = {},
            onRemove = {},
        )
    }
}
