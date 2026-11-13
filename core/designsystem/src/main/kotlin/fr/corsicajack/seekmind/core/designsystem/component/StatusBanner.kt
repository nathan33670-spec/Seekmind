package fr.corsicajack.seekmind.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import fr.corsicajack.seekmind.core.designsystem.R
import fr.corsicajack.seekmind.core.designsystem.SeekMindIcons
import fr.corsicajack.seekmind.core.designsystem.preview.SeekMindPreviews
import fr.corsicajack.seekmind.core.designsystem.theme.SeekMindTheme
import fr.corsicajack.seekmind.core.designsystem.theme.Spacing

/**
 * Banner shown when memorisation is not active (docs/specs/ui-ux.md §3.2), with one action such as
 * « Reprendre ». Changes are announced politely by accessibility services.
 */
@Composable
fun StatusBanner(message: String, actionLabel: String?, onAction: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.heightIn(min = Spacing.minTouchTarget).padding(start = Spacing.l, end = Spacing.s),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            Icon(painter = painterResource(SeekMindIcons.Info), contentDescription = null)
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(vertical = Spacing.m),
            )
            if (actionLabel != null) {
                TextButton(onClick = onAction) { Text(text = actionLabel) }
            }
        }
    }
}

@SeekMindPreviews
@Composable
private fun StatusBannerPreview() {
    SeekMindTheme(dynamicColor = false) {
        StatusBanner(
            message = stringResource(R.string.designsystem_preview_banner),
            actionLabel = stringResource(R.string.designsystem_preview_banner_action),
            onAction = {},
        )
    }
}
