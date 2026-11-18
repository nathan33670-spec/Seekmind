package fr.corsicajack.seekmind.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * [ActivationSettingsSection] bound to its [ActivationSettingsViewModel]. « Coller » reads the clipboard
 * once, on the user's tap, puts the text in the field and activates it.
 */
@Composable
fun ActivationSettingsRoute(
    viewModel: ActivationSettingsViewModel,
    modifier: Modifier = Modifier,
    privacyOptionsRequired: Boolean = false,
    onPrivacyOptions: () -> Unit = {},
) {
    @Suppress("DEPRECATION") // LocalClipboard is suspending and needs a ClipEntry; the text is all we read.
    val clipboard = LocalClipboardManager.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    ActivationSettingsSection(
        state = state,
        onInputChange = viewModel::setInput,
        onPaste = {
            viewModel.setInput(clipboard.getText()?.text.orEmpty())
            viewModel.activate()
        },
        onActivate = viewModel::activate,
        onRemove = viewModel::deactivate,
        modifier = modifier,
        privacyOptionsRequired = privacyOptionsRequired,
        onPrivacyOptions = onPrivacyOptions,
    )
}
