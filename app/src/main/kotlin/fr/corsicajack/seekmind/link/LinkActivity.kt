package fr.corsicajack.seekmind.link

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dagger.hilt.android.AndroidEntryPoint
import fr.corsicajack.seekmind.MainActivity
import fr.corsicajack.seekmind.R
import fr.corsicajack.seekmind.SecureActivity
import fr.corsicajack.seekmind.core.designsystem.theme.SeekMindTheme
import fr.corsicajack.seekmind.core.designsystem.theme.Spacing
import fr.corsicajack.seekmind.engine.monetization.license.ActivationResult
import fr.corsicajack.seekmind.engine.monetization.license.LicenseActivator
import fr.corsicajack.seekmind.feature.settings.activationRefusalText
import fr.corsicajack.seekmind.lock.AppLock
import fr.corsicajack.seekmind.lock.BiometricAuthenticator
import fr.corsicajack.seekmind.lock.LockGate
import fr.corsicajack.seekmind.lock.LockState
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val LINK_CONFIRM_TAG = "link_confirm"
const val LINK_DECLINE_TAG = "link_decline"
const val LINK_MESSAGE_TAG = "link_message"

/**
 * Receives `seekmind://activate?t=<code>` (docs/specs/monetisation.md §5.4, docs/specs/ui-ux.md §3.7).
 * The link is validated, then nothing is done before the user confirms. Behind the application lock: a
 * link received while locked is handled after the unlock. Exported for this link only (INV-12).
 */
@AndroidEntryPoint
class LinkActivity : SecureActivity() {
    @Inject lateinit var appLock: AppLock

    @Inject lateinit var activator: dagger.Lazy<LicenseActivator>

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Read once: a later intent opens a new confirmation (singleTop is not used).
        val link = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString
        setContent {
            SeekMindTheme {
                val lockState by appLock.state.collectAsStateWithLifecycle(initialValue = LockState.PENDING)
                LockGate(
                    state = lockState,
                    onUnlock = { BiometricAuthenticator.authenticate(this, appLock::onAuthenticated) },
                ) {
                    val model = viewModel {
                        LinkViewModel(link) { code -> withContext(Dispatchers.IO) { activator.get().activate(code) } }
                    }
                    val step by model.step.collectAsStateWithLifecycle()
                    LinkScreen(
                        step = step,
                        onConfirm = model::confirm,
                        onDecline = {
                            model.decline()
                            finish()
                        },
                        onOpen = {
                            startActivity(Intent(this, MainActivity::class.java))
                            finish()
                        },
                        onClose = ::finish,
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        appLock.onForeground()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) appLock.onBackground()
    }
}

@Composable
private fun LinkScreen(
    step: LinkStep,
    onConfirm: () -> Unit,
    onDecline: () -> Unit,
    onOpen: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.safeDrawingPadding().padding(Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.l),
        ) {
            when (step) {
                LinkStep.Confirm -> {
                    Title(stringResource(R.string.link_confirm_title))
                    Text(text = stringResource(R.string.link_confirm_text), style = MaterialTheme.typography.bodyLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
                        OutlinedButton(onClick = onDecline, modifier = Modifier.testTag(LINK_DECLINE_TAG)) {
                            Text(text = stringResource(R.string.link_decline))
                        }
                        Button(onClick = onConfirm, modifier = Modifier.testTag(LINK_CONFIRM_TAG)) {
                            Text(text = stringResource(R.string.link_confirm))
                        }
                    }
                }

                LinkStep.Working -> CircularProgressIndicator()

                is LinkStep.Done -> {
                    val result = step.result
                    Message(
                        if (result is ActivationResult.Refused) {
                            activationRefusalText(result.reason)
                        } else {
                            stringResource(R.string.link_activated)
                        },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
                        OutlinedButton(onClick = onClose) { Text(text = stringResource(R.string.link_close)) }
                        Button(onClick = onOpen) { Text(text = stringResource(R.string.link_open)) }
                    }
                }

                LinkStep.Invalid, LinkStep.Declined -> {
                    Message(stringResource(R.string.link_invalid))
                    OutlinedButton(onClick = onClose) { Text(text = stringResource(R.string.link_close)) }
                }
            }
        }
    }
}

@Composable
private fun Title(text: String) {
    Text(text = text, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
}

@Composable
private fun Message(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag(LINK_MESSAGE_TAG),
    )
}
