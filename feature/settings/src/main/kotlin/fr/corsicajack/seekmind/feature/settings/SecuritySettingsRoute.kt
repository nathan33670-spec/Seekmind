package fr.corsicajack.seekmind.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** [SecuritySettingsSection] bound to its [SecuritySettingsViewModel]. */
@Composable
fun SecuritySettingsRoute(viewModel: SecuritySettingsViewModel, modifier: Modifier = Modifier) {
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose {}
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    state?.let {
        SecuritySettingsSection(
            state = it,
            onLockEnabledChange = viewModel::setLockEnabled,
            onGraceChange = viewModel::setGraceMs,
            modifier = modifier,
        )
    }
}
