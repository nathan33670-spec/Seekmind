package fr.corsicajack.seekmind.lock

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import fr.corsicajack.seekmind.R
import fr.corsicajack.seekmind.core.designsystem.theme.Spacing

/** Test tag of the neutral screen. */
const val LOCK_SCREEN_TAG = "lock_screen"

/**
 * Composes [content] only when [state] is [LockState.UNLOCKED]; otherwise the neutral screen, with no
 * remembered content in the interface tree (docs/specs/ui-ux.md §3.6).
 */
@Composable
fun LockGate(state: LockState, onUnlock: () -> Unit, content: @Composable () -> Unit) {
    when (state) {
        LockState.UNLOCKED -> content()
        LockState.LOCKED -> LockScreen(onUnlock = onUnlock)
        LockState.PENDING -> LockScreen(onUnlock = null)
    }
}

/** Logo, name and « Déverrouiller »; the button is absent while the state is not known yet. */
@Composable
fun LockScreen(onUnlock: (() -> Unit)?) {
    Surface(modifier = Modifier.fillMaxSize().testTag(LOCK_SCREEN_TAG)) {
        Column(
            modifier = Modifier.safeDrawingPadding().padding(Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.l, Alignment.CenterVertically),
        ) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.size(96.dp),
            )
            Text(text = stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
            if (onUnlock != null) {
                Text(
                    text = stringResource(R.string.app_lock_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onUnlock) { Text(text = stringResource(R.string.app_lock_unlock)) }
            }
        }
    }
}
