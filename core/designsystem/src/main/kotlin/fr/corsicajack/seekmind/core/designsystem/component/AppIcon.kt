package fr.corsicajack.seekmind.core.designsystem.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.corsicajack.seekmind.core.designsystem.R
import fr.corsicajack.seekmind.core.designsystem.preview.SeekMindPreviews
import fr.corsicajack.seekmind.core.designsystem.theme.SeekMindTheme

/**
 * Icon of an application, or its initial in a circle while the icon is not loaded or unavailable.
 * Decorative: the application name is always written next to it. Loading and caching the icons off
 * the main thread is up to the caller (docs/specs/ui-ux.md §7).
 */
@Composable
fun AppIcon(icon: ImageBitmap?, appLabel: String, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    if (icon != null) {
        Image(bitmap = icon, contentDescription = null, modifier = modifier.size(size).clip(CircleShape))
    } else {
        Surface(
            modifier = modifier.size(size),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(text = initialOf(appLabel), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/** First letter or digit of [label], upper-cased; empty when there is none. */
internal fun initialOf(label: String): String {
    val index = label.indexOfFirst { it.isLetterOrDigit() }
    if (index < 0) return ""
    val end = if (Character.isHighSurrogate(label[index]) && index + 1 < label.length) index + 2 else index + 1
    return label.substring(index, end).uppercase()
}

@SeekMindPreviews
@Composable
private fun AppIconPreview() {
    SeekMindTheme(dynamicColor = false) {
        AppIcon(icon = null, appLabel = stringResource(R.string.designsystem_preview_app))
    }
}
