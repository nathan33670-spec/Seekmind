package fr.corsicajack.seekmind.feature.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.corsicajack.seekmind.core.designsystem.SeekMindIcons
import fr.corsicajack.seekmind.core.designsystem.component.ConfirmDialog
import fr.corsicajack.seekmind.core.designsystem.component.EmptyState
import fr.corsicajack.seekmind.core.designsystem.component.FilterChipItem
import fr.corsicajack.seekmind.core.designsystem.component.FilterChipRow
import fr.corsicajack.seekmind.core.designsystem.component.ResultCard
import fr.corsicajack.seekmind.core.designsystem.component.ResultCardModel
import fr.corsicajack.seekmind.core.designsystem.component.SearchField
import fr.corsicajack.seekmind.core.designsystem.component.StatusBanner
import fr.corsicajack.seekmind.core.designsystem.theme.Spacing
import fr.corsicajack.seekmind.core.model.Relaxation
import fr.corsicajack.seekmind.core.model.SearchHit
import fr.corsicajack.seekmind.core.model.TextSource
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** Test tags of the screen. */
const val SEARCH_RESULTS_TAG = "search_results"
const val SEARCH_REFINING_TAG = "search_refining"

private const val APP_CHIP = "app"

/** The search screen bound to its [SearchViewModel]. */
@Composable
fun SearchRoute(
    viewModel: SearchViewModel,
    zone: ZoneId,
    now: () -> Long,
    onOpenResult: (SearchHit) -> Unit,
    onOpenSource: (SearchHit) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SearchScreen(
        state = state,
        zone = zone,
        now = now,
        onQueryChange = viewModel::onQueryChange,
        onSearch = viewModel::submit,
        onPeriod = viewModel::setPeriod,
        onApp = viewModel::setApp,
        onRemoveUnderstood = viewModel::removeUnderstood,
        onOpenResult = onOpenResult,
        onOpenSource = onOpenSource,
        onReset = onReset,
        modifier = modifier,
    )
}

/**
 * The search (docs/specs/ui-ux.md §3.2): field, « Période » and « Application » chips, the filters
 * understood in the query as removable chips, then the content of the current [SearchPhase].
 */
@Composable
fun SearchScreen(
    state: SearchUiState,
    zone: ZoneId,
    now: () -> Long,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    onPeriod: (Period?) -> Unit,
    onApp: (String?) -> Unit,
    onRemoveUnderstood: (UnderstoodFilter) -> Unit,
    onOpenResult: (SearchHit) -> Unit,
    onOpenSource: (SearchHit) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = Spacing.l),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        SearchField(
            query = state.query,
            onQueryChange = onQueryChange,
            onSearch = onSearch,
            placeholder = stringResource(R.string.search_placeholder),
            modifier = Modifier.padding(top = Spacing.m),
        )
        Filters(state, onPeriod, onApp)
        if (state.understood.isNotEmpty()) {
            FilterChipRow(
                items = state.understood.map { FilterChipItem(it.key, it.label(), selected = true, removable = true) },
                onToggle = { key -> state.understood.firstOrNull { it.key == key }?.let(onRemoveUnderstood) },
                onRemove = { key -> state.understood.firstOrNull { it.key == key }?.let(onRemoveUnderstood) },
            )
        }
        if (state.refining) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().testTag(SEARCH_REFINING_TAG))
        }
        Box(modifier = Modifier.weight(1f)) {
            when (state.phase) {
                SearchPhase.IDLE -> Idle(state, onSearch)

                SearchPhase.SEARCHING -> Unit

                SearchPhase.RESULTS -> Results(state, zone, now, onOpenResult, onOpenSource)

                SearchPhase.NO_RESULT -> EmptyState(
                    title = stringResource(R.string.search_no_result_title),
                    message = stringResource(R.string.search_no_result_message) +
                        if (state.notCharged) "\n" + stringResource(R.string.search_not_charged) else "",
                    icon = SeekMindIcons.Search,
                )

                SearchPhase.NO_CREDIT -> EmptyState(
                    title = stringResource(R.string.search_no_credit_title),
                    message = stringResource(R.string.search_no_credit_message),
                    icon = SeekMindIcons.Info,
                )

                SearchPhase.UNAVAILABLE -> Unavailable(onReset)
            }
        }
    }
}

@Composable
private fun Filters(state: SearchUiState, onPeriod: (Period?) -> Unit, onApp: (String?) -> Unit) {
    var appMenu by remember { mutableStateOf(false) }
    val appLabel = state.apps.firstOrNull { it.packageName == state.app }?.label ?: stringResource(R.string.search_app)
    val chips = buildList {
        Period.entries.forEach { add(FilterChipItem(it.name, it.label(), selected = state.period == it)) }
        add(FilterChipItem(APP_CHIP, appLabel, selected = state.app != null, removable = state.app != null))
    }
    Box {
        FilterChipRow(
            items = chips,
            onToggle = { key ->
                if (key == APP_CHIP) {
                    appMenu = true
                } else {
                    val period = Period.valueOf(key)
                    onPeriod(if (state.period == period) null else period)
                }
            },
            onRemove = { key -> if (key == APP_CHIP) onApp(null) },
        )
        DropdownMenu(expanded = appMenu, onDismissRequest = { appMenu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.search_all_apps)) }, onClick = {
                appMenu = false
                onApp(null)
            })
            state.apps.forEach { app ->
                DropdownMenuItem(text = { Text(app.label) }, onClick = {
                    appMenu = false
                    onApp(app.packageName)
                })
            }
        }
    }
}

@Composable
private fun Idle(state: SearchUiState, onSearch: (String) -> Unit) {
    val examples = listOf(
        stringResource(R.string.search_example_1),
        stringResource(R.string.search_example_2),
        stringResource(R.string.search_example_3),
    )
    LazyColumn(
        contentPadding = PaddingValues(vertical = Spacing.m),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        if (state.recent.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.search_recent)) }
            items(state.recent) { query -> SuggestionRow(query) { onSearch(query) } }
        }
        item { SectionTitle(stringResource(R.string.search_examples)) }
        items(examples) { example -> SuggestionRow(example) { onSearch(example) } }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(vertical = Spacing.s).semantics { heading() },
    )
}

@Composable
private fun SuggestionRow(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(text = text, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun Results(
    state: SearchUiState,
    zone: ZoneId,
    now: () -> Long,
    onOpenResult: (SearchHit) -> Unit,
    onOpenSource: (SearchHit) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag(SEARCH_RESULTS_TAG),
        contentPadding = PaddingValues(vertical = Spacing.s),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        if (state.relaxed != null) {
            item {
                val message = when {
                    state.relaxed == Relaxation.TIME && state.relaxedLabel != null ->
                        stringResource(R.string.search_relaxed_time_label, state.relaxedLabel)

                    state.relaxed == Relaxation.TIME -> stringResource(R.string.search_relaxed_time)

                    else -> stringResource(R.string.search_relaxed_all)
                }
                StatusBanner(message = message, actionLabel = null, onAction = {})
            }
        }
        if (state.pendingVectors > 0) {
            item {
                Text(
                    text = pluralStringResource(
                        R.plurals.search_index_incomplete,
                        state.pendingVectors,
                        state.pendingVectors,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        } else if (!state.semanticAvailable) {
            item {
                Text(text = stringResource(R.string.search_keywords_only), style = MaterialTheme.typography.bodySmall)
            }
        }
        items(state.results, key = { it.hit.captureId }) { item ->
            ResultCard(
                model = item.toCardModel(zone, now()),
                icon = null,
                onClick = { onOpenResult(item.hit) },
                onSourceAction = { onOpenSource(item.hit) },
            )
        }
    }
}

@Composable
private fun Unavailable(onReset: () -> Unit) {
    var confirm by rememberSaveable { mutableStateOf(false) }
    EmptyState(
        title = stringResource(R.string.search_unavailable_title),
        message = stringResource(R.string.search_unavailable_message),
        icon = SeekMindIcons.Info,
        actionLabel = stringResource(R.string.search_reset),
        onAction = { confirm = true },
    )
    if (confirm) {
        ConfirmDialog(
            title = stringResource(R.string.search_reset_title),
            text = stringResource(R.string.search_reset_text),
            confirmLabel = stringResource(R.string.search_reset),
            dismissLabel = stringResource(R.string.search_cancel),
            onConfirm = {
                confirm = false
                onReset()
            },
            onDismiss = { confirm = false },
            destructive = true,
        )
    }
}

@Composable
private fun Period.label(): String = stringResource(
    when (this) {
        Period.TODAY -> R.string.search_period_today
        Period.YESTERDAY -> R.string.search_period_yesterday
        Period.WEEK -> R.string.search_period_week
        Period.MONTH -> R.string.search_period_month
    },
)

private fun UnderstoodFilter.label(): String = when (this) {
    is UnderstoodFilter.Range -> label
    is UnderstoodFilter.App -> label
}

@Composable
private fun SearchResultItem.toCardModel(zone: ZoneId, now: Long): ResultCardModel {
    val hit = hit
    val url = hit.url
    val host = url?.let { runCatching { URI(it).host }.getOrNull() }
    return ResultCardModel(
        appLabel = appLabel,
        timeLabel = timeLabel(hit.lastSeenAt, now, zone),
        title = hit.windowTitle ?: host,
        snippet = hit.snippet.text,
        highlights = hit.snippet.highlights,
        fromImage = hit.textSource and TextSource.OCR != 0,
        similarCount = hit.similarCount,
        // Until the return to the source (M7): the page when the screen had a URL, otherwise the application.
        sourceActionLabel = if (url != null) {
            stringResource(R.string.search_open_page)
        } else {
            stringResource(R.string.search_open_app, appLabel)
        },
    )
}

/** « 21:12 » today, « hier 21:12 », « mardi 21:12 » within a week, « 3 nov. 21:12 » before. */
@Composable
private fun timeLabel(at: Long, now: Long, zone: ZoneId): String {
    val locale = LocalConfiguration.current.locales[0]
    val time = Instant.ofEpochMilli(at).atZone(zone)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val clock = time.format(DateTimeFormatter.ofPattern("HH:mm", locale))
    val days = ChronoUnit.DAYS.between(time.toLocalDate(), today)
    return when {
        days <= 0L -> clock
        days == 1L -> stringResource(R.string.search_yesterday_at, clock)
        days < 7L -> time.format(DateTimeFormatter.ofPattern("EEEE HH:mm", locale))
        else -> time.format(DateTimeFormatter.ofPattern("d MMM HH:mm", locale))
    }
}
