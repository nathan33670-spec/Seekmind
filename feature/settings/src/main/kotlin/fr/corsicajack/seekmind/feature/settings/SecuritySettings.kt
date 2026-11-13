package fr.corsicajack.seekmind.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import fr.corsicajack.seekmind.core.designsystem.component.SettingRow
import fr.corsicajack.seekmind.core.designsystem.component.SwitchSettingRow
import fr.corsicajack.seekmind.core.designsystem.preview.SeekMindPreviews
import fr.corsicajack.seekmind.core.designsystem.theme.SeekMindTheme
import fr.corsicajack.seekmind.core.designsystem.theme.Spacing

/** Test tag of the lock switch. */
const val LOCK_SWITCH_TAG = "settings_lock_switch"

/**
 * « Sécurité » section of the settings (docs/specs/ui-ux.md §3.5, §3.6): the application lock and its
 * grace delay. Without a secure screen lock on the device, the switch is disabled and says why.
 */
@Composable
fun SecuritySettingsSection(
    state: SecurityUiState,
    onLockEnabledChange: (Boolean) -> Unit,
    onGraceChange: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var choosingGrace by rememberSaveable { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.settings_security_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s).semantics { heading() },
        )
        SwitchSettingRow(
            title = stringResource(R.string.settings_lock_title),
            summary = stringResource(
                if (state.deviceSecure) R.string.settings_lock_summary else R.string.settings_lock_unavailable,
            ),
            checked = state.lockEnabled && state.deviceSecure,
            onCheckedChange = onLockEnabledChange,
            enabled = state.deviceSecure,
            modifier = Modifier.testTag(LOCK_SWITCH_TAG),
        )
        if (state.lockEnabled && state.deviceSecure) {
            SettingRow(
                title = stringResource(R.string.settings_lock_grace_title),
                summary = graceLabel(state.graceMs),
                onClick = { choosingGrace = true },
            )
        }
    }
    if (choosingGrace) {
        GraceDialog(
            current = state.graceMs,
            onChoose = {
                onGraceChange(it)
                choosingGrace = false
            },
            onDismiss = { choosingGrace = false },
        )
    }
}

@Composable
private fun GraceDialog(current: Long, onChoose: (Long) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.settings_lock_grace_title)) },
        text = {
            Column(modifier = Modifier.selectableGroup()) {
                GRACE_CHOICES_MS.forEach { ms ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = Spacing.minTouchTarget)
                            .selectable(selected = ms == current, role = Role.RadioButton, onClick = { onChoose(ms) }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = ms == current, onClick = null)
                        Text(text = graceLabel(ms), modifier = Modifier.padding(start = Spacing.m))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(text = stringResource(R.string.settings_cancel)) } },
    )
}

@Composable
private fun graceLabel(ms: Long): String {
    val minutes = (ms / 60_000L).toInt()
    return if (minutes == 0) {
        stringResource(R.string.settings_lock_grace_immediately)
    } else {
        pluralStringResource(R.plurals.settings_lock_grace_minutes, minutes, minutes)
    }
}

/** Grace delays offered (docs/specs/ui-ux.md §3.6: 5 minutes by default). */
val GRACE_CHOICES_MS: List<Long> = listOf(0L, 60_000L, 5 * 60_000L, 15 * 60_000L)

@SeekMindPreviews
@Composable
private fun SecuritySettingsSectionPreview() {
    SeekMindTheme(dynamicColor = false) {
        SecuritySettingsSection(
            state = SecurityUiState(lockEnabled = true, graceMs = 5 * 60_000L, deviceSecure = true),
            onLockEnabledChange = {},
            onGraceChange = {},
        )
    }
}
