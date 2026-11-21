package fr.corsicajack.seekmind.feature.settings

import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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
import fr.corsicajack.seekmind.core.database.store.StorageStats
import fr.corsicajack.seekmind.core.designsystem.component.ConfirmDialog
import fr.corsicajack.seekmind.core.designsystem.component.SettingRow
import fr.corsicajack.seekmind.core.designsystem.preview.SeekMindPreviews
import fr.corsicajack.seekmind.core.designsystem.theme.SeekMindTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Test tag of the retention row. */
const val RETENTION_ROW_TAG = "settings_retention"

/** Test tag of the « Tout effacer » row. */
const val WIPE_ROW_TAG = "settings_wipe"

/** Test tag of the row of deletion by period. */
const val DELETE_PERIOD_ROW_TAG = "settings_delete_period"

/** Test tag of the row of deletion by application. */
const val DELETE_APP_ROW_TAG = "settings_delete_app"

/**
 * « Données » (docs/specs/ui-ux.md §3.5): retention, storage budget, what is used, deletions by period and by
 * application, « Tout effacer » with the confirmation word.
 */
@Composable
fun DataSettingsSection(
    state: DataUiState,
    zone: ZoneId,
    onRetention: (Int?) -> Unit,
    onBudget: (Long) -> Unit,
    onDeletePeriod: (DeletePeriod) -> Unit,
    onDeleteApp: (InstalledApp) -> Unit,
    onMessageShown: () -> Unit,
    onWipe: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    var period by rememberSaveable { mutableStateOf<DeletePeriod?>(null) }
    var appToDelete by rememberSaveable { mutableStateOf<String?>(null) }
    Column(modifier = modifier.fillMaxWidth()) {
        SectionTitle(stringResource(R.string.settings_data_title))
        SettingRow(
            title = stringResource(R.string.settings_data_retention),
            summary = retentionLabel(state.retentionDays),
            onClick = { dialog = RETENTION },
            modifier = Modifier.testTag(RETENTION_ROW_TAG),
        )
        SettingRow(
            title = stringResource(R.string.settings_data_budget),
            summary = sizeLabel(state.budgetBytes),
            onClick = { dialog = BUDGET },
        )
        SettingRow(
            title = stringResource(R.string.settings_data_used),
            summary = state.stats?.let { usedLabel(it, zone) } ?: stringResource(R.string.settings_data_used_unknown),
        )
        SettingRow(
            title = stringResource(R.string.settings_data_delete_period),
            onClick = { dialog = PERIOD },
            modifier = Modifier.testTag(DELETE_PERIOD_ROW_TAG),
        )
        if (state.apps.isNotEmpty()) {
            SettingRow(
                title = stringResource(R.string.settings_data_delete_app),
                onClick = { dialog = APP },
                modifier = Modifier.testTag(DELETE_APP_ROW_TAG),
            )
        }
        SettingRow(
            title = stringResource(R.string.settings_data_wipe),
            summary = stringResource(R.string.settings_data_wipe_summary),
            onClick = { dialog = WIPE },
            modifier = Modifier.testTag(WIPE_ROW_TAG),
        )
        state.message?.let { message ->
            SectionMessage(
                when (message) {
                    is DataMessage.PeriodDeleted -> stringResource(R.string.settings_data_deleted_period)
                    is DataMessage.AppDeleted -> stringResource(R.string.settings_data_deleted_app, message.app.label)
                },
            )
        }
    }
    val close = { dialog = null }
    when (dialog) {
        RETENTION -> ChoiceDialog(
            title = stringResource(R.string.settings_data_retention),
            choices = DataSettingsViewModel.RETENTION_CHOICES,
            selected = state.retentionDays,
            label = { retentionLabel(it) },
            onChoose = {
                close()
                onRetention(it)
            },
            onDismiss = close,
        )

        BUDGET -> ChoiceDialog(
            title = stringResource(R.string.settings_data_budget),
            choices = DataSettingsViewModel.BUDGET_CHOICES,
            selected = state.budgetBytes,
            label = { sizeLabel(it) },
            onChoose = {
                close()
                onBudget(it)
            },
            onDismiss = close,
        )

        PERIOD -> ChoiceDialog(
            title = stringResource(R.string.settings_data_delete_period),
            choices = DeletePeriod.entries,
            selected = null,
            label = { periodLabel(it) },
            onChoose = {
                close()
                onMessageShown()
                period = it
            },
            onDismiss = close,
        )

        APP -> ChoiceDialog(
            title = stringResource(R.string.settings_data_delete_app),
            choices = state.apps,
            selected = null,
            label = { it.label },
            onChoose = {
                close()
                onMessageShown()
                appToDelete = it.packageName
            },
            onDismiss = close,
        )

        WIPE -> WipeDialog(
            onConfirm = {
                close()
                onWipe()
            },
            onDismiss = close,
        )
    }
    period?.let { chosen ->
        ConfirmDialog(
            title = stringResource(R.string.settings_data_delete_period_title, periodLabel(chosen)),
            text = stringResource(R.string.settings_data_delete_text),
            confirmLabel = stringResource(R.string.settings_data_delete_confirm),
            dismissLabel = stringResource(R.string.settings_cancel),
            onConfirm = {
                period = null
                onDeletePeriod(chosen)
            },
            onDismiss = { period = null },
            destructive = true,
        )
    }
    appToDelete?.let { packageName ->
        val app = state.apps.firstOrNull { it.packageName == packageName } ?: return@let
        ConfirmDialog(
            title = stringResource(R.string.settings_data_delete_app_title, app.label),
            text = stringResource(R.string.settings_data_delete_text),
            confirmLabel = stringResource(R.string.settings_data_delete_confirm),
            dismissLabel = stringResource(R.string.settings_cancel),
            onConfirm = {
                appToDelete = null
                onDeleteApp(app)
            },
            onDismiss = { appToDelete = null },
            destructive = true,
        )
    }
}

private const val RETENTION = "retention"
private const val BUDGET = "budget"
private const val PERIOD = "period"
private const val APP = "app"
private const val WIPE = "wipe"

@Composable
private fun retentionLabel(days: Int?): String = if (days == null) {
    stringResource(R.string.settings_data_retention_unlimited)
} else {
    pluralStringResource(R.plurals.settings_data_retention_days, days, days)
}

@Composable
private fun sizeLabel(bytes: Long): String = Formatter.formatShortFileSize(LocalContext.current, bytes)

@Composable
private fun usedLabel(stats: StorageStats, zone: ZoneId): String {
    val count = pluralStringResource(R.plurals.settings_data_count, stats.captureCount, stats.captureCount)
    val size = sizeLabel(stats.sizeBytes)
    val oldest = stats.oldestCaptureAt ?: return stringResource(R.string.settings_data_used_summary, count, size)
    val date = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(Instant.ofEpochMilli(oldest).atZone(zone))
    return stringResource(R.string.settings_data_used_since, count, size, date)
}

@Composable
private fun periodLabel(period: DeletePeriod): String = stringResource(
    when (period) {
        DeletePeriod.LAST_HOUR -> R.string.settings_data_period_hour
        DeletePeriod.LAST_DAY -> R.string.settings_data_period_day
        DeletePeriod.LAST_7_DAYS -> R.string.settings_data_period_week
        DeletePeriod.LAST_30_DAYS -> R.string.settings_data_period_month
    },
)

@SeekMindPreviews
@Composable
private fun DataSettingsSectionPreview() {
    SeekMindTheme(dynamicColor = false) {
        DataSettingsSection(
            state = DataUiState(90, 500L * 1024 * 1024, StorageStats(1_204, 48L * 1024 * 1024, 0L), emptyList(), null),
            zone = ZoneId.of("Europe/Paris"),
            onRetention = {},
            onBudget = {},
            onDeletePeriod = {},
            onDeleteApp = {},
            onMessageShown = {},
            onWipe = {},
        )
    }
}
