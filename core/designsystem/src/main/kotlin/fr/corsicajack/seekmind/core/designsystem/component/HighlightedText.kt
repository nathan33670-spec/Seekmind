package fr.corsicajack.seekmind.core.designsystem.component

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import fr.corsicajack.seekmind.core.designsystem.R
import fr.corsicajack.seekmind.core.designsystem.preview.SeekMindPreviews
import fr.corsicajack.seekmind.core.designsystem.theme.SeekMindTheme

/**
 * [text] with the character ranges [highlights] (inclusive, as in `Snippet.highlights`) emphasised.
 * The emphasis is bold on a tinted background, so that it is not carried by colour alone
 * (docs/specs/ui-ux.md §5).
 */
@Composable
fun HighlightedText(
    text: String,
    highlights: List<IntRange>,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    maxLines: Int = Int.MAX_VALUE,
) {
    val colors = MaterialTheme.colorScheme
    val span = SpanStyle(
        fontWeight = FontWeight.Bold,
        color = colors.onPrimaryContainer,
        background = colors.primaryContainer,
    )
    val annotated = remember(text, highlights, span) { highlight(text, highlights, span) }
    Text(text = annotated, modifier = modifier, style = style, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

/**
 * Applies [style] to each range of [highlights]. Ranges are clipped to the text; empty, reversed and
 * out-of-bounds ranges are ignored, and overlapping or adjacent ranges are merged.
 */
fun highlight(text: String, highlights: List<IntRange>, style: SpanStyle): AnnotatedString = buildAnnotatedString {
    append(text)
    mergedRanges(text.length, highlights).forEach { addStyle(style, it.first, it.last + 1) }
}

/** The valid parts of [ranges] within a text of [length] characters, sorted and merged. */
internal fun mergedRanges(length: Int, ranges: List<IntRange>): List<IntRange> {
    val clipped = ranges
        .map { maxOf(it.first, 0)..minOf(it.last, length - 1) }
        .filter { !it.isEmpty() }
        .sortedBy { it.first }
    val merged = ArrayList<IntRange>(clipped.size)
    for (range in clipped) {
        val last = merged.lastOrNull()
        if (last != null && range.first <= last.last + 1) {
            merged[merged.lastIndex] = last.first..maxOf(last.last, range.last)
        } else {
            merged += range
        }
    }
    return merged
}

@SeekMindPreviews
@Composable
private fun HighlightedTextPreview() {
    SeekMindTheme(dynamicColor = false) {
        val text = stringResource(R.string.designsystem_preview_snippet)
        HighlightedText(text = text, highlights = listOf(10..19, 60..72))
    }
}
