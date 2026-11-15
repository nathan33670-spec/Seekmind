package fr.corsicajack.seekmind.feature.journal

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.corsicajack.seekmind.core.designsystem.SeekMindIcons
import fr.corsicajack.seekmind.core.designsystem.component.ConfirmDialog
import fr.corsicajack.seekmind.core.designsystem.component.EmptyState
import fr.corsicajack.seekmind.core.designsystem.component.FilterChipItem
import fr.corsicajack.seekmind.core.designsystem.component.FilterChipRow
import fr.corsicajack.seekmind.core.designsystem.preview.SeekMindPreviews
import fr.corsicajack.seekmind.core.designsystem.theme.SeekMindTheme
import fr.corsicajack.seekmind.core.designsystem.theme.Spacing
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Test tag of the list. */
const val JOURNAL_LIST_TAG = "journal_list"

/** Lines left below the last visible one when the next page is requested. */
private const val PREFETCH_DISTANCE = 15

/** Key of the « every application » chip: no package name contains a star. */
private const val ALL_APPS_KEY = "*"

/** The journal bound to its [JournalViewModel]. */
@Composable
fun JournalRoute(
    viewModel: JournalViewModel,
    zone: ZoneId,
    today: () -> LocalDate,
    modifier: Modifier = Modifier,
    onOpen: (Long) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    JournalScreen(
        state = state,
        zone = zone,
        today = today,
        onLoadMore = viewModel::loadMore,
        onFilter = viewModel::setFilter,
        onDelete = viewModel::requestDelete,
        onUndo = viewModel::undoDelete,
        onCommit = viewModel::commitPending,
        onDeleteDay = viewModel::deleteDay,
        modifier = modifier,
        onOpen = onOpen,
    )
}

/**
 * The journal (docs/specs/ui-ux.md §3.4): filter by application, lines grouped by day under sticky
 * headers, the next page requested near the end of the list, swipe to delete with « Annuler ».
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun JournalScreen(
    state: JournalUiState,
    zone: ZoneId,
    today: () -> LocalDate,
    onLoadMore: () -> Unit,
    onFilter: (String?) -> Unit,
    onDelete: (Long) -> Unit,
    onUndo: () -> Unit,
    onCommit: () -> Unit,
    onDeleteDay: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    /** Opens the detail of a line (EF-JRN-02). */
    onOpen: (Long) -> Unit = {},
) {
    val snackbar = remember { SnackbarHostState() }
    val deletedMessage = stringResource(R.string.journal_deleted)
    val undoLabel = stringResource(R.string.journal_undo)
    val currentUndo by rememberUpdatedState(onUndo)
    val currentCommit by rememberUpdatedState(onCommit)
    LaunchedEffect(state.pendingDeletion) {
        if (state.pendingDeletion == null) return@LaunchedEffect
        val result = snackbar.showSnackbar(deletedMessage, actionLabel = undoLabel, duration = SnackbarDuration.Short)
        if (result == SnackbarResult.ActionPerformed) currentUndo() else currentCommit()
    }
    val currentLoadMore by rememberUpdatedState(onLoadMore)
    LaunchedEffect(listState) {
        // The item count is part of the key: when a page arrives and the end is still in sight
        // (a tall screen), the next page is requested too.
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            (last >= info.totalItemsCount - PREFETCH_DISTANCE) to info.totalItemsCount
        }.collect { (nearEnd, _) -> if (nearEnd) currentLoadMore() }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (state.apps.size > 1 || state.filter != null) {
                FilterChipRow(
                    items = listOf(
                        FilterChipItem(
                            ALL_APPS_KEY,
                            stringResource(R.string.journal_all_apps),
                            selected =
                                state.filter == null,
                        ),
                    ) +
                        state.apps.map {
                            FilterChipItem(
                                it.packageName,
                                it.label,
                                selected =
                                    it.packageName == state.filter,
                            )
                        },
                    onToggle = { key -> onFilter(key.takeUnless { it == ALL_APPS_KEY }) },
                    contentPadding = PaddingValues(horizontal = Spacing.l, vertical = Spacing.s),
                )
            }
            when {
                state.unavailable -> EmptyState(
                    title = stringResource(R.string.journal_unavailable_title),
                    message = stringResource(R.string.journal_unavailable_message),
                )

                state.empty -> EmptyState(
                    title = stringResource(R.string.journal_empty_title),
                    message = stringResource(R.string.journal_empty_message),
                    icon = SeekMindIcons.Journal,
                )

                else -> JournalList(state, zone, today, listState, onDelete, onDeleteDay, onOpen)
            }
        }
        SnackbarHost(hostState = snackbar, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun JournalList(
    state: JournalUiState,
    zone: ZoneId,
    today: () -> LocalDate,
    listState: LazyListState,
    onDelete: (Long) -> Unit,
    onDeleteDay: (LocalDate) -> Unit,
    onOpen: (Long) -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    val timeFormat = remember(locale) { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale) }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag(JOURNAL_LIST_TAG)) {
        state.days.forEach { day ->
            stickyHeader(key = "day-${day.date}", contentType = "day") {
                DayHeader(date = day.date, today = today(), locale = locale, onDeleteDay = onDeleteDay)
            }
            items(day.entries, key = { it.captureId }, contentType = { "entry" }) { entry ->
                SwipeToDeleteRow(
                    entry = entry,
                    time = timeFormat.format(Instant.ofEpochMilli(entry.lastSeenAt).atZone(zone)),
                    onDelete = onDelete,
                    onOpen = onOpen,
                )
            }
        }
        if (!state.endReached) {
            // No key: a stable key would keep the loader as the anchor of the scroll position, and the
            // list would jump to the end when the lines of a page arrive above it.
            item(contentType = "loading") {
                Box(modifier = Modifier.fillMaxWidth().padding(Spacing.l), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

@Composable
private fun DayHeader(date: LocalDate, today: LocalDate, locale: Locale, onDeleteDay: (LocalDate) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var confirming by rememberSaveable { mutableStateOf(false) }
    val label = dayLabel(date, today, locale)
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.heightIn(min = Spacing.minTouchTarget).padding(start = Spacing.l),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(
                        painter = painterResource(SeekMindIcons.More),
                        contentDescription = stringResource(R.string.journal_day_menu, label),
                    )
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(text = stringResource(R.string.journal_delete_day)) },
                        onClick = {
                            menu = false
                            confirming = true
                        },
                    )
                }
            }
        }
    }
    if (confirming) {
        ConfirmDialog(
            title = stringResource(R.string.journal_delete_day_title, label),
            text = stringResource(R.string.journal_delete_day_text),
            confirmLabel = stringResource(R.string.journal_delete),
            dismissLabel = stringResource(R.string.journal_cancel),
            onConfirm = {
                confirming = false
                onDeleteDay(date)
            },
            onDismiss = { confirming = false },
            destructive = true,
        )
    }
}

@Composable
private fun SwipeToDeleteRow(entry: JournalEntry, time: String, onDelete: (Long) -> Unit, onOpen: (Long) -> Unit) {
    val currentDelete by rememberUpdatedState(onDelete)
    val swipe = rememberSwipeToDismissBoxState()
    val deleteLabel = stringResource(R.string.journal_delete)
    SwipeToDismissBox(
        state = swipe,
        backgroundContent = {
            Box(
                modifier = Modifier.fillMaxSize().background(
                    MaterialTheme.colorScheme.errorContainer,
                ).padding(horizontal = Spacing.xl),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    painter = painterResource(SeekMindIcons.Delete),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        },
        modifier = Modifier.semantics {
            customActions = listOf(
                CustomAccessibilityAction(deleteLabel) {
                    currentDelete(entry.captureId)
                    true
                },
            )
        },
        onDismiss = { value -> if (value != SwipeToDismissBoxValue.Settled) currentDelete(entry.captureId) },
    ) {
        JournalRow(entry = entry, time = time, onOpen = onOpen)
    }
}

@Composable
private fun JournalRow(entry: JournalEntry, time: String, onOpen: (Long) -> Unit) {
    Surface(onClick = { onOpen(entry.captureId) }, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.heightIn(
                min = Spacing.minTouchTarget,
            ).padding(horizontal = Spacing.l, vertical = Spacing.m),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Text(
                    text = entry.appLabel,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    text = time,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (entry.headline.isNotEmpty()) {
                Text(
                    text = entry.headline,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    HorizontalDivider()
}

/** « Aujourd'hui », « Hier », or the day in full, with the year when it is not the current one. */
@Composable
private fun dayLabel(date: LocalDate, today: LocalDate, locale: Locale): String = when (date) {
    today -> stringResource(R.string.journal_today)

    today.minusDays(1) -> stringResource(R.string.journal_yesterday)

    else -> {
        val pattern = if (date.year == today.year) "EEEE d MMMM" else "EEEE d MMMM yyyy"
        DateTimeFormatter.ofPattern(pattern, locale).format(date).replaceFirstChar { it.titlecase(locale) }
    }
}

@SeekMindPreviews
@Composable
private fun JournalScreenPreview() {
    SeekMindTheme(dynamicColor = false) {
        val zone = ZoneId.of("Europe/Paris")
        val today = LocalDate.of(2026, 11, 13)
        val at = today.atTime(21, 12).atZone(zone).toInstant().toEpochMilli()
        val app = stringResource(R.string.journal_preview_app)
        val line = stringResource(R.string.journal_preview_line)
        val entries = (0 until 4).map { JournalEntry(it.toLong(), at - it * 20 * 3_600_000L, "app", app, line) }
        JournalScreen(
            state = JournalUiState(days = byDay(entries, zone), loading = false, endReached = true),
            zone = zone,
            today = { today },
            onLoadMore = {},
            onFilter = {},
            onDelete = {},
            onUndo = {},
            onCommit = {},
            onDeleteDay = {},
        )
    }
}
