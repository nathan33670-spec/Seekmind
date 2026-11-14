package fr.corsicajack.seekmind.feature.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import fr.corsicajack.seekmind.core.designsystem.component.StatusBanner
import fr.corsicajack.seekmind.core.designsystem.theme.Spacing
import fr.corsicajack.seekmind.core.model.CaptureStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Test tag of the capture status banner. */
const val SEARCH_STATUS_TAG = "search_status"

/**
 * The banner at the top of the search when memorisation is not active (docs/specs/ui-ux.md §3.2,
 * EF-CAP-05): paused → « Reprendre »; service turned off → « Réactiver », and the troubleshooting page;
 * consent missing → « Activer la mémorisation », only when [canEnable] (the disclosure exists).
 * Nothing when active or not known yet.
 */
@Composable
fun CaptureStatusBanner(
    status: CaptureStatus?,
    now: Long,
    zone: ZoneId,
    canEnable: Boolean,
    onAction: (StatusAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (status) {
        null, is CaptureStatus.Active -> Unit

        is CaptureStatus.Paused -> StatusBanner(
            message = status.until?.let { stringResource(R.string.search_status_paused_until, endOf(it, now, zone)) }
                ?: stringResource(R.string.search_status_paused),
            actionLabel = stringResource(R.string.search_status_resume),
            onAction = { onAction(StatusAction.RESUME) },
            modifier = modifier.testTag(SEARCH_STATUS_TAG),
        )

        CaptureStatus.ServiceDisabled -> Column(
            modifier = modifier.testTag(SEARCH_STATUS_TAG),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            StatusBanner(
                message = stringResource(R.string.search_status_disabled),
                actionLabel = stringResource(R.string.search_status_reactivate),
                onAction = { onAction(StatusAction.REACTIVATE) },
            )
            TextButton(onClick = { onAction(StatusAction.HELP) }) {
                Text(stringResource(R.string.search_status_help))
            }
        }

        CaptureStatus.ConsentMissing -> StatusBanner(
            message = stringResource(R.string.search_status_consent),
            actionLabel = if (canEnable) stringResource(R.string.search_status_enable) else null,
            onAction = { onAction(StatusAction.ENABLE) },
            modifier = modifier.testTag(SEARCH_STATUS_TAG),
        )
    }
}

/** « Dernière mémorisation il y a 2 min » when memorisation is active and something was remembered. */
@Composable
fun LastCaptureLine(status: CaptureStatus?, now: Long, modifier: Modifier = Modifier) {
    val at = (status as? CaptureStatus.Active)?.lastCaptureAt ?: return
    val text = when (val elapsed = Elapsed.between(at, now)) {
        Elapsed.JustNow -> stringResource(R.string.search_last_capture_now)
        is Elapsed.Minutes -> pluralStringResource(R.plurals.search_last_capture_minutes, elapsed.count, elapsed.count)
        is Elapsed.Hours -> pluralStringResource(R.plurals.search_last_capture_hours, elapsed.count, elapsed.count)
        is Elapsed.Days -> pluralStringResource(R.plurals.search_last_capture_days, elapsed.count, elapsed.count)
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/** « 14:30 » the same day, the date and time otherwise. */
@Composable
private fun endOf(until: Long, now: Long, zone: ZoneId): String {
    val locale = LocalConfiguration.current.locales[0]
    val end = Instant.ofEpochMilli(until).atZone(zone)
    val sameDay = end.toLocalDate() == Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val formatter = remember(locale, sameDay) {
        if (sameDay) {
            DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        } else {
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
        }.withLocale(locale)
    }
    return end.format(formatter)
}
