package fr.corsicajack.seekmind.feature.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.corsicajack.seekmind.core.model.AppFamily

/** [OnboardingScreen] bound to its [OnboardingViewModel]; [onFinished] when the flow ends. */
@Composable
fun OnboardingRoute(
    viewModel: OnboardingViewModel,
    familyApps: Map<AppFamily, List<FamilyApp>>,
    onOpenAccessibilitySettings: () -> Unit,
    onHelp: () -> Unit,
    onFinished: (refused: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    /** « Télécharger » on the language pack screen, once the flow has ended. */
    onDownloadPack: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.step) {
        if (state.step == OnboardingStep.DONE) {
            if (state.downloadPack) onDownloadPack()
            onFinished(state.refused)
        }
    }
    OnboardingScreen(
        state = state,
        familyApps = familyApps,
        onNextIntro = viewModel::nextIntroPage,
        onSkipIntro = viewModel::skipIntro,
        onAccept = viewModel::accept,
        onRefuse = viewModel::refuse,
        onToggleFamily = viewModel::toggleFamily,
        onConfirmFamilies = viewModel::confirmFamilies,
        // Never without the consent and the families recorded (INV-04).
        onOpenSettings = { if (viewModel.mayOpenSystemSettings()) onOpenAccessibilitySettings() },
        onHelp = onHelp,
        onContinueAfterConfirmation = viewModel::continueAfterConfirmation,
        onChooseLock = viewModel::chooseLock,
        onChoosePack = viewModel::choosePack,
        onLeave = viewModel::leave,
        modifier = modifier,
    )
}
