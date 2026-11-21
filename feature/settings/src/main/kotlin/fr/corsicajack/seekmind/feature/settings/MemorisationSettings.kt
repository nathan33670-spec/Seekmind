package fr.corsicajack.seekmind.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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
import fr.corsicajack.seekmind.core.designsystem.preview.SeekMindPreviews
import fr.corsicajack.seekmind.core.designsystem.theme.SeekMindTheme
import fr.corsicajack.seekmind.core.model.CaptureStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Test tag of the pause row. */
const val PAUSE_ROW_TAG = "settings_pause"

/** Test tag of the resume row. */
const val RESUME_ROW_TAG = "settings_resume"

/** Test tag of the withdrawal row. */
const val WITHDRAW_ROW_TAG = "settings_withdraw"

/**
 * « Mémorisation » (docs/specs/ui-ux.md §3.5): the state of the service; pause (1 h, until tomorrow, without
 * limit) or resumption; rereading the disclosure; withdrawing the consent, which offers to erase the data.
 */
@Composable
fun MemorisationSettingsSection(
    state: MemorisationUiState,
    zone: ZoneId,
    onPause: (PauseChoice) -> Unit,
    onResume: () -> Unit,
    onActivate: () -> Unit,
    onReadDisclosure: () -> Unit,
    onWithdraw: () -> Unit,
    onDismissWipeOffer: () -> Unit,
    onWipe: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var choosingPause by rememberSaveable { mutableStateOf(false) }
    var confirmingWithdraw by rememberSaveable { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth()) {
        SectionTitle(stringResource(R.string.settings_memorisation_title))
        when (val status = state.status) {
            is CaptureStatus.Active -> SettingRow(
                title = stringResource(R.string.settings_memorisation_active),
                summary = stringResource(R.string.settings_memorisation_pause),
                onClick = { choosingPause = true },
                modifier = Modifier.testTag(PAUSE_ROW_TAG),
            )

            is CaptureStatus.Paused -> SettingRow(
                title = pausedLabel(status.until, zone),
                summary = stringResource(R.string.settings_memorisation_resume),
                onClick = onResume,
                modifier = Modifier.testTag(RESUME_ROW_TAG),
            )

            CaptureStatus.ServiceDisabled -> SettingRow(
                title = stringResource(R.string.settings_memorisation_disabled),
                summary = stringResource(R.string.settings_memorisation_activate),
                onClick = onActivate,
            )

            CaptureStatus.ConsentMissing -> SettingRow(
                title = stringResource(R.string.settings_memorisation_no_consent),
                summary = stringResource(R.string.settings_memorisation_no_consent_summary),
                onClick = onReadDisclosure,
            )
        }
        SettingRow(
            title = stringResource(R.string.settings_memorisation_disclosure),
            onClick = onReadDisclosure,
        )
        if (state.consented) {
            SettingRow(
                title = stringResource(R.string.settings_memorisation_withdraw),
                summary = stringResource(R.string.settings_memorisation_withdraw_summary),
                onClick = { confirmingWithdraw = true },
                modifier = Modifier.testTag(WITHDRAW_ROW_TAG),
            )
        }
    }
    if (choosingPause) {
        ChoiceDialog(
            title = stringResource(R.string.settings_memorisation_pause),
            choices = PauseChoice.entries,
            selected = null,
            label = { pauseLabel(it) },
            onChoose = {
                choosingPause = false
                onPause(it)
            },
            onDismiss = { choosingPause = false },
        )
    }
    if (confirmingWithdraw) {
        ConfirmDialog(
            title = stringResource(R.string.settings_memorisation_withdraw_title),
            text = stringResource(R.string.settings_memorisation_withdraw_text),
            confirmLabel = stringResource(R.string.settings_memorisation_withdraw_confirm),
            dismissLabel = stringResource(R.string.settings_cancel),
            onConfirm = {
                confirmingWithdraw = false
                onWithdraw()
            },
            onDismiss = { confirmingWithdraw = false },
            destructive = true,
        )
    }
    if (state.offerWipe) {
        ConfirmDialog(
            title = stringResource(R.string.settings_memorisation_withdrawn_title),
            text = stringResource(R.string.settings_memorisation_withdrawn_text),
            confirmLabel = stringResource(R.string.settings_data_wipe),
            dismissLabel = stringResource(R.string.settings_memorisation_keep_data),
            onConfirm = {
                onDismissWipeOffer()
                onWipe()
            },
            onDismiss = onDismissWipeOffer,
            destructive = true,
        )
    }
}

@Composable
private fun pauseLabel(choice: PauseChoice): String = stringResource(
    when (choice) {
        PauseChoice.ONE_HOUR -> R.string.settings_pause_one_hour
        PauseChoice.UNTIL_TOMORROW_MORNING -> R.string.settings_pause_tomorrow
        PauseChoice.INDEFINITELY -> R.string.settings_pause_indefinitely
    },
)

@Composable
private fun pausedLabel(until: Long?, zone: ZoneId): String = if (until == null || until == Long.MAX_VALUE) {
    stringResource(R.string.settings_memorisation_paused)
} else {
    val time = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).format(Instant.ofEpochMilli(until).atZone(zone))
    stringResource(R.string.settings_memorisation_paused_until, time)
}

@SeekMindPreviews
@Composable
private fun MemorisationSettingsSectionPreview() {
    SeekMindTheme(dynamicColor = false) {
        MemorisationSettingsSection(
            state = MemorisationUiState(CaptureStatus.Active(null), consented = true),
            zone = ZoneId.of("Europe/Paris"),
            onPause = {},
            onResume = {},
            onActivate = {},
            onReadDisclosure = {},
            onWithdraw = {},
            onDismissWipeOffer = {},
            onWipe = {},
        )
    }
}
