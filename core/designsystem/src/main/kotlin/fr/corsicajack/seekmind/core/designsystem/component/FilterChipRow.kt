package fr.corsicajack.seekmind.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import fr.corsicajack.seekmind.core.designsystem.R
import fr.corsicajack.seekmind.core.designsystem.SeekMindIcons
import fr.corsicajack.seekmind.core.designsystem.preview.SeekMindPreviews
import fr.corsicajack.seekmind.core.designsystem.theme.SeekMindTheme
import fr.corsicajack.seekmind.core.designsystem.theme.Spacing

/**
 * One chip of a [FilterChipRow]. A [removable] chip shows a filter understood from the query
 * (« mardi soir », « Instagram ») and is removed by a tap; the others toggle.
 */
@Immutable
data class FilterChipItem(
    val key: String,
    val label: String,
    val selected: Boolean = false,
    val removable: Boolean = false,
)

/** Horizontally scrolling row of filter chips (docs/specs/ui-ux.md §3.2). */
@Composable
fun FilterChipRow(
    items: List<FilterChipItem>,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
    onRemove: (String) -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(),
) {
    LazyRow(
        modifier = modifier,
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        items(items, key = { it.key }) { item ->
            if (item.removable) {
                val removeLabel = stringResource(R.string.designsystem_filter_remove, item.label)
                InputChip(
                    selected = true,
                    onClick = { onRemove(item.key) },
                    label = { Text(text = item.label) },
                    trailingIcon = {
                        Icon(
                            painter = painterResource(SeekMindIcons.Close),
                            contentDescription = removeLabel,
                            modifier = Modifier.size(InputChipDefaults.IconSize),
                        )
                    },
                )
            } else {
                FilterChip(
                    selected = item.selected,
                    onClick = { onToggle(item.key) },
                    label = { Text(text = item.label) },
                    leadingIcon = if (item.selected) {
                        {
                            Icon(
                                painter = painterResource(SeekMindIcons.Check),
                                contentDescription = null,
                                modifier = Modifier.size(FilterChipDefaults.IconSize),
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

@SeekMindPreviews
@Composable
private fun FilterChipRowPreview() {
    SeekMindTheme(dynamicColor = false) {
        FilterChipRow(
            items = listOf(
                FilterChipItem("app", stringResource(R.string.designsystem_preview_chip_app), removable = true),
                FilterChipItem("today", stringResource(R.string.designsystem_preview_chip_today), selected = true),
                FilterChipItem("week", stringResource(R.string.designsystem_preview_chip_week)),
            ),
            onToggle = {},
        )
    }
}
