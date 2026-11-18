package fr.corsicajack.seekmind.feature.paywall

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import fr.corsicajack.seekmind.core.designsystem.theme.Spacing
import fr.corsicajack.seekmind.engine.monetization.RewardMessage

const val CREDITS_SHEET_TAG = "credits_sheet"
const val CREDITS_AD_TAG = "credits_ad"
const val CREDITS_CODE_TAG = "credits_code"
const val CREDITS_MESSAGE_TAG = "credits_message"

/**
 * « Continuer à chercher » (docs/specs/monetisation.md §6): the rewarded ad, the activation code and the
 * privacy reminder. Without ads ([AdButton.ABSENT]) no ad button is composed; [dailyFreeCredits] > 0
 * then says how many searches are offered each day.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreditsSheet(
    state: CreditsSheetState,
    dailyFreeCredits: Int,
    onWatch: () -> Unit,
    onCode: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag(CREDITS_SHEET_TAG)) {
        CreditsSheetContent(state, dailyFreeCredits, onWatch, onCode)
    }
}

@Composable
fun CreditsSheetContent(state: CreditsSheetState, dailyFreeCredits: Int, onWatch: () -> Unit, onCode: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(
            horizontal = Spacing.xl,
            vertical = Spacing.l,
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Text(
            text = stringResource(R.string.paywall_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        if (state.button != AdButton.ABSENT) {
            Button(
                onClick = onWatch,
                enabled = state.button == AdButton.READY,
                modifier = Modifier.fillMaxWidth().testTag(CREDITS_AD_TAG),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                ) {
                    if (state.button == AdButton.LOADING || state.button == AdButton.WATCHING) {
                        CircularProgressIndicator(modifier = Modifier.size(Spacing.l), strokeWidth = Spacing.xs / 2)
                    }
                    Text(text = stringResource(R.string.paywall_watch))
                }
            }
            if (state.button == AdButton.CEILING) {
                Text(text = stringResource(R.string.paywall_ceiling), style = MaterialTheme.typography.bodyMedium)
            }
        } else if (dailyFreeCredits > 0) {
            Text(
                text = pluralStringResource(R.plurals.paywall_daily_free, dailyFreeCredits, dailyFreeCredits),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        OutlinedButton(onClick = onCode, modifier = Modifier.fillMaxWidth().testTag(CREDITS_CODE_TAG)) {
            Text(text = stringResource(R.string.paywall_code))
        }
        state.message?.let { message ->
            Text(
                text = messageText(message),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag(CREDITS_MESSAGE_TAG),
            )
        }
        if (state.button != AdButton.ABSENT) {
            Text(
                text = stringResource(R.string.paywall_privacy),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun messageText(message: RewardMessage): String = when (message) {
    is RewardMessage.Earned -> pluralStringResource(R.plurals.paywall_earned, message.credits, message.credits)
    RewardMessage.Dismissed -> stringResource(R.string.paywall_dismissed)
    is RewardMessage.Courtesy -> pluralStringResource(R.plurals.paywall_courtesy, message.credits, message.credits)
    RewardMessage.Unavailable -> stringResource(R.string.paywall_unavailable)
    RewardMessage.CeilingReached -> stringResource(R.string.paywall_ceiling)
}
