package fr.corsicajack.seekmind.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp
import fr.corsicajack.seekmind.core.designsystem.R
import fr.corsicajack.seekmind.core.designsystem.SeekMindIcons
import fr.corsicajack.seekmind.core.designsystem.preview.SeekMindPreviews
import fr.corsicajack.seekmind.core.designsystem.theme.SeekMindTheme
import fr.corsicajack.seekmind.core.designsystem.theme.Spacing

/** What the [CreditBadge] shows (docs/specs/monetisation.md, docs/specs/ui-ux.md §6). */
@Immutable
sealed interface CreditBadgeState {
    /** Free use: number of searches left. */
    data class Credits(val remaining: Int) : CreditBadgeState

    /** Activated with a licence: unlimited searches. */
    data object Activated : CreditBadgeState
}

/**
 * Badge next to the search field: « 7 recherches » or « Activé ». A tap opens the credits sheet.
 * Read by accessibility services as « 7 recherches restantes ».
 */
@Composable
fun CreditBadge(state: CreditBadgeState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val (visible, spoken) = when (state) {
        is CreditBadgeState.Credits -> Pair(
            pluralStringResource(R.plurals.designsystem_credits_short, state.remaining, state.remaining),
            pluralStringResource(R.plurals.designsystem_credits_remaining, state.remaining, state.remaining),
        )

        CreditBadgeState.Activated -> stringResource(R.string.designsystem_activated).let { Pair(it, it) }
    }
    Surface(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = Spacing.minTouchTarget)
            .clearAndSetSemantics {
                contentDescription = spoken
                role = Role.Button
                onClick {
                    onClick()
                    true
                }
            },
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.m),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            if (state == CreditBadgeState.Activated) {
                Icon(
                    painter = painterResource(SeekMindIcons.Check),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(
                text = visible,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
        }
    }
}

@SeekMindPreviews
@Composable
private fun CreditBadgePreview() {
    SeekMindTheme(dynamicColor = false) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            CreditBadge(state = CreditBadgeState.Credits(7), onClick = {})
            CreditBadge(state = CreditBadgeState.Activated, onClick = {})
        }
    }
}
