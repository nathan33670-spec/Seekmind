package fr.corsicajack.seekmind.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import fr.corsicajack.seekmind.core.designsystem.component.ConfirmDialog
import fr.corsicajack.seekmind.core.designsystem.component.SettingRow
import fr.corsicajack.seekmind.core.designsystem.component.SwitchSettingRow
import fr.corsicajack.seekmind.core.designsystem.preview.SeekMindPreviews
import fr.corsicajack.seekmind.core.designsystem.theme.SeekMindTheme
import fr.corsicajack.seekmind.core.model.DropReason

/** Test tag of the history switch. */
const val HISTORY_SWITCH_TAG = "settings_history"

/** Test tag of the « Partager le diagnostic » row. */
const val SHARE_DIAGNOSTIC_TAG = "settings_share_diagnostic"

/** « Recherche » (docs/specs/ui-ux.md §3.5): the history, on or off, and its deletion. */
@Composable
fun HistorySettingsSection(
    state: HistoryUiState,
    onEnabledChange: (Boolean) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth()) {
        SectionTitle(stringResource(R.string.settings_history_title))
        SwitchSettingRow(
            title = stringResource(R.string.settings_history_enabled),
            summary = stringResource(R.string.settings_history_summary),
            checked = state.enabled,
            onCheckedChange = onEnabledChange,
            modifier = Modifier.testTag(HISTORY_SWITCH_TAG),
        )
        SettingRow(title = stringResource(R.string.settings_history_clear), onClick = { confirming = true })
        if (state.cleared) SectionMessage(stringResource(R.string.settings_history_cleared))
    }
    if (confirming) {
        ConfirmDialog(
            title = stringResource(R.string.settings_history_clear_title),
            text = stringResource(R.string.settings_history_clear_text),
            confirmLabel = stringResource(R.string.settings_history_clear_confirm),
            dismissLabel = stringResource(R.string.settings_cancel),
            onConfirm = {
                confirming = false
                onClear()
            },
            onDismiss = { confirming = false },
            destructive = true,
        )
    }
}

/**
 * « Diagnostic » (docs/specs/ui-ux.md §3.5): the counters of the day and of the last 7 days, and
 * « Partager le diagnostic », a text of counter names and numbers.
 */
@Composable
fun DiagnosticSettingsSection(state: DiagnosticUiState, onShare: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        SectionTitle(stringResource(R.string.settings_diagnostic_title))
        SettingRow(title = stringResource(R.string.settings_diagnostic_today), summary = countersLabel(state.today))
        SettingRow(title = stringResource(R.string.settings_diagnostic_week), summary = countersLabel(state.lastWeek))
        SettingRow(
            title = stringResource(R.string.settings_diagnostic_pending),
            summary = state.pendingFragments.toString(),
        )
        SettingRow(
            title = stringResource(R.string.settings_diagnostic_share),
            summary = stringResource(R.string.settings_diagnostic_share_summary),
            onClick = onShare,
            modifier = Modifier.testTag(SHARE_DIAGNOSTIC_TAG),
        )
    }
}

@Composable
private fun countersLabel(counters: Counters): String {
    val lines = mutableListOf(
        stringResource(R.string.settings_diagnostic_stored, counters.stored),
        stringResource(R.string.settings_diagnostic_deduplicated, counters.deduplicated),
    )
    counters.dropped.forEach { (reason, count) ->
        lines += stringResource(R.string.settings_diagnostic_dropped, dropLabel(reason), count)
    }
    return lines.joinToString("\n")
}

@Composable
private fun dropLabel(reason: DropReason): String = stringResource(
    when (reason) {
        DropReason.CONSENT_MISSING -> R.string.settings_drop_consent
        DropReason.PAUSED -> R.string.settings_drop_paused
        DropReason.DEVICE_LOCKED -> R.string.settings_drop_locked
        DropReason.NO_ROOT, DropReason.NOT_APP_WINDOW -> R.string.settings_drop_window
        DropReason.APP_IGNORED -> R.string.settings_drop_ignored
        DropReason.APP_EXCLUDED -> R.string.settings_drop_excluded
        DropReason.APP_FAMILY_DISABLED -> R.string.settings_drop_family
        DropReason.APP_SENSITIVE -> R.string.settings_drop_sensitive
        DropReason.APP_CATEGORY -> R.string.settings_drop_game
        DropReason.PASSWORD_FIELD -> R.string.settings_drop_password
        DropReason.PRIVATE_BROWSING -> R.string.settings_drop_private
        DropReason.SECURE_WINDOW -> R.string.settings_drop_secure
        DropReason.TREE_TIMEOUT -> R.string.settings_drop_timeout
        DropReason.TOO_SHORT -> R.string.settings_drop_short
        DropReason.ERROR -> R.string.settings_drop_error
    },
)

/**
 * « À propos » (docs/specs/ui-ux.md §3.5): the version, the privacy policy (opens the browser) and the
 * third-party licences.
 */
@Composable
fun AboutSettingsSection(
    version: String,
    onPrivacyPolicy: () -> Unit,
    licences: String,
    modifier: Modifier = Modifier,
) {
    var showLicences by rememberSaveable { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth()) {
        SectionTitle(stringResource(R.string.settings_about_title))
        SettingRow(title = stringResource(R.string.settings_about_version), summary = version)
        SettingRow(
            title = stringResource(R.string.settings_about_privacy),
            summary = stringResource(R.string.settings_about_privacy_summary),
            onClick = onPrivacyPolicy,
        )
        SettingRow(title = stringResource(R.string.settings_about_licences), onClick = { showLicences = true })
    }
    if (showLicences) {
        AlertDialog(
            onDismissRequest = { showLicences = false },
            title = { Text(text = stringResource(R.string.settings_about_licences)) },
            text = { Text(text = licences, modifier = Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = {
                TextButton(onClick = { showLicences = false }) { Text(text = stringResource(R.string.settings_close)) }
            },
        )
    }
}

@SeekMindPreviews
@Composable
private fun DiagnosticSettingsSectionPreview() {
    SeekMindTheme(dynamicColor = false) {
        DiagnosticSettingsSection(
            state = DiagnosticUiState(
                today = Counters.of(mapOf("capture_stored" to 12, "capture_dropped_password_field" to 2)),
                lastWeek = Counters.of(mapOf("capture_stored" to 80)),
                pendingFragments = 3,
            ),
            onShare = {},
        )
    }
}
