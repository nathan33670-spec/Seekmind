package fr.corsicajack.seekmind.feature.search.detail

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.corsicajack.seekmind.core.designsystem.SeekMindIcons
import fr.corsicajack.seekmind.core.designsystem.component.ConfirmDialog
import fr.corsicajack.seekmind.core.designsystem.component.EmptyState
import fr.corsicajack.seekmind.core.designsystem.component.HighlightedText
import fr.corsicajack.seekmind.core.designsystem.theme.Spacing
import fr.corsicajack.seekmind.core.model.Capture
import fr.corsicajack.seekmind.core.model.TextSource
import fr.corsicajack.seekmind.feature.search.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Test tag of the whole text. */
const val DETAIL_TEXT_TAG = "detail_text"

/** The detail bound to its [CaptureDetailViewModel]; [onClose] is called once the capture is deleted. */
@Composable
fun CaptureDetailRoute(
    viewModel: CaptureDetailViewModel,
    zone: ZoneId,
    onOpenSource: (Capture) -> Unit,
    onOpenLink: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.deleted) { if (state.deleted) onClose() }
    CaptureDetailScreen(
        state = state,
        zone = zone,
        onOpenSource = onOpenSource,
        onOpenLink = onOpenLink,
        onDelete = viewModel::delete,
        modifier = modifier,
    )
}

/**
 * Detail of a capture (docs/specs/ui-ux.md §3.3): application, first and last seen, views, title, URL;
 * the whole text, selectable, search terms highlighted; return to the source, copy, share, delete with
 * confirmation; the links found in the text. Copies are marked sensitive ([SensitiveClipboard]).
 */
@Composable
fun CaptureDetailScreen(
    state: CaptureDetailState,
    zone: ZoneId,
    onOpenSource: (Capture) -> Unit,
    onOpenLink: (String) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val capture = state.capture
    when {
        state.loading -> Unit

        state.unavailable -> EmptyState(
            title = stringResource(R.string.search_unavailable_title),
            message = stringResource(R.string.detail_unavailable_message),
            icon = SeekMindIcons.Info,
            modifier = modifier,
        )

        state.missing || capture == null -> EmptyState(
            title = stringResource(R.string.detail_missing_title),
            message = stringResource(R.string.detail_missing_message),
            icon = SeekMindIcons.Info,
            modifier = modifier,
        )

        else -> Detail(state, capture, zone, onOpenSource, onOpenLink, onDelete, modifier)
    }
}

@Composable
private fun Detail(
    state: CaptureDetailState,
    capture: Capture,
    zone: ZoneId,
    onOpenSource: (Capture) -> Unit,
    onOpenLink: (String) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val formatter =
        remember(locale) {
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale)
        }
    fun format(at: Long) = Instant.ofEpochMilli(at).atZone(zone).format(formatter)
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val clipLabel = stringResource(R.string.detail_clip_label)
    val shareTitle = stringResource(R.string.detail_share)
    val clipboard = SensitiveClipboard(LocalClipboard.current)

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.l),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Text(
            text = capture.windowTitle ?: state.appLabel,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        Text(text = state.appLabel, style = MaterialTheme.typography.titleSmall)
        Text(
            text = stringResource(R.string.detail_seen, format(capture.firstSeenAt), format(capture.lastSeenAt)),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = pluralStringResource(R.plurals.detail_views, capture.seenCount, capture.seenCount),
            style = MaterialTheme.typography.bodySmall,
        )
        capture.url?.let { Text(text = it, style = MaterialTheme.typography.bodySmall) }
        if (capture.textSource and TextSource.OCR != 0) {
            Text(text = stringResource(R.string.detail_from_image), style = MaterialTheme.typography.labelMedium)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            OutlinedButton(onClick = { onOpenSource(capture) }) {
                Text(
                    if (capture.url != null) {
                        stringResource(R.string.search_open_page)
                    } else {
                        stringResource(R.string.search_open_app, state.appLabel)
                    },
                )
            }
            OutlinedButton(onClick = { copy(context, sensitiveClip(clipLabel, capture.text)) }) {
                Text(stringResource(R.string.detail_copy))
            }
            OutlinedButton(onClick = { share(context, capture.text, shareTitle) }) { Text(shareTitle) }
            OutlinedButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.detail_delete)) }
        }
        if (state.deleteFailed) {
            Text(
                text = stringResource(R.string.detail_delete_failed),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        CompositionLocalProvider(LocalClipboard provides clipboard) {
            SelectionContainer {
                HighlightedText(
                    text = capture.text,
                    highlights = state.highlights,
                    modifier = Modifier.fillMaxWidth().testTag(DETAIL_TEXT_TAG),
                )
            }
        }
        if (state.links.isNotEmpty()) {
            Text(
                text = stringResource(R.string.detail_links),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { heading() },
            )
            state.links.forEach { link -> TextButton(onClick = { onOpenLink(link) }) { Text(link) } }
        }
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.detail_delete_title),
            text = stringResource(R.string.detail_delete_text),
            confirmLabel = stringResource(R.string.detail_delete),
            dismissLabel = stringResource(R.string.search_cancel),
            onConfirm = {
                confirmDelete = false
                onDelete()
            },
            onDismiss = { confirmDelete = false },
            destructive = true,
        )
    }
}

private fun copy(context: Context, clip: android.content.ClipData) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
}

/** The text to another application chosen by the user (an explicit action, INV-06). */
private fun share(context: Context, text: String, title: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, title))
}
