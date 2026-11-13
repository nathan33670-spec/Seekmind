package fr.corsicajack.seekmind.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.corsicajack.seekmind.core.designsystem.R
import fr.corsicajack.seekmind.core.designsystem.SeekMindIcons
import fr.corsicajack.seekmind.core.designsystem.preview.SeekMindPreviews
import fr.corsicajack.seekmind.core.designsystem.theme.SeekMindTheme
import fr.corsicajack.seekmind.core.designsystem.theme.Spacing

/**
 * Content of a [ResultCard] (docs/specs/ui-ux.md §3.2). Texts are already formatted by the caller:
 * [timeLabel] is the relative date and time (« mardi 21:12 »), [title] the page title or the host of
 * the URL, [sourceActionLabel] the exact label of the return to the source (docs/specs/retour-source.md §1).
 */
@Immutable
data class ResultCardModel(
    val appLabel: String,
    val timeLabel: String,
    val title: String?,
    val snippet: String,
    val highlights: List<IntRange>,
    val fromImage: Boolean,
    val similarCount: Int,
    val sourceActionLabel: String,
)

/** Maximum number of lines of the extract. */
const val RESULT_SNIPPET_MAX_LINES = 3

/**
 * A search result. A tap opens the detail; the button returns to the source. Accessibility services
 * read the card as one sentence and also offer the return to the source as a custom action.
 */
@Composable
fun ResultCard(
    model: ResultCardModel,
    icon: ImageBitmap?,
    onClick: () -> Unit,
    onSourceAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction(model.sourceActionLabel) {
                        onSourceAction()
                        true
                    },
                )
            },
    ) {
        Column(modifier = Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.m),
            ) {
                AppIcon(icon = icon, appLabel = model.appLabel)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = model.appLabel,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = model.timeLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!model.title.isNullOrBlank()) {
                Text(
                    text = model.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            HighlightedText(
                text = model.snippet,
                highlights = model.highlights,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = RESULT_SNIPPET_MAX_LINES,
            )
            if (model.fromImage || model.similarCount > 0) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.m),
                ) {
                    if (model.fromImage) {
                        Icon(
                            painter = painterResource(SeekMindIcons.Image),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(R.string.designsystem_result_from_image),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (model.similarCount > 0) {
                        Text(
                            text = pluralStringResource(
                                R.plurals.designsystem_result_similar,
                                model.similarCount,
                                model.similarCount,
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            OutlinedButton(onClick = onSourceAction, modifier = Modifier.align(Alignment.End)) {
                Text(text = model.sourceActionLabel)
            }
        }
    }
}

@SeekMindPreviews
@Composable
private fun ResultCardPreview() {
    SeekMindTheme(dynamicColor = false) {
        ResultCard(
            model = ResultCardModel(
                appLabel = stringResource(R.string.designsystem_preview_app),
                timeLabel = stringResource(R.string.designsystem_preview_time),
                title = stringResource(R.string.designsystem_preview_title),
                snippet = stringResource(R.string.designsystem_preview_snippet),
                highlights = listOf(10..19),
                fromImage = true,
                similarCount = 3,
                sourceActionLabel = stringResource(R.string.designsystem_preview_source),
            ),
            icon = null,
            onClick = {},
            onSourceAction = {},
            modifier = Modifier.padding(Spacing.l),
        )
    }
}
