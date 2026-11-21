package fr.corsicajack.seekmind.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import fr.corsicajack.seekmind.BuildConfig
import fr.corsicajack.seekmind.R
import fr.corsicajack.seekmind.core.common.SmLog
import fr.corsicajack.seekmind.feature.settings.AboutSettingsSection
import fr.corsicajack.seekmind.feature.settings.AppsListScreen
import fr.corsicajack.seekmind.feature.settings.AppsSettingsSection
import fr.corsicajack.seekmind.feature.settings.DataSettingsSection
import fr.corsicajack.seekmind.feature.settings.DiagnosticSettingsSection
import fr.corsicajack.seekmind.feature.settings.DiagnosticSettingsViewModel
import fr.corsicajack.seekmind.feature.settings.HistorySettingsSection
import fr.corsicajack.seekmind.feature.settings.LanguageSettingsSection
import fr.corsicajack.seekmind.feature.settings.MemorisationSettingsSection
import fr.corsicajack.seekmind.feature.settings.R as SettingsR
import java.time.ZoneId

/**
 * Settings (docs/specs/ui-ux.md §3.5), in the order of the spec: Mémorisation, Applications, Données,
 * Sécurité ([security]), Langues de recherche, Recherche, Activation ([activation]), Diagnostic, À propos.
 */
@Composable
fun SettingsScreen(
    bindings: SettingsBindings,
    openApps: () -> Unit,
    onReadDisclosure: () -> Unit,
    onActivate: () -> Unit,
    onWipe: () -> Unit,
    security: @Composable () -> Unit,
    activation: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val zone = remember { ZoneId.systemDefault() }
    val memorisation = viewModel { bindings.memorisation() }
    val apps = viewModel(key = "settings-apps") { bindings.apps() }
    val data = viewModel { bindings.data() }
    val languages = viewModel { bindings.languages() }
    val history = viewModel { bindings.history() }
    val diagnostic = viewModel { bindings.diagnostic() }
    LifecycleResumeEffect(data, diagnostic) {
        data.refresh()
        diagnostic.refresh()
        onPauseOrDispose {}
    }
    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        memorisation.state.collectAsStateWithLifecycle().value?.let {
            MemorisationSettingsSection(
                state = it,
                zone = zone,
                onPause = memorisation::pause,
                onResume = memorisation::resume,
                onActivate = onActivate,
                onReadDisclosure = onReadDisclosure,
                onWithdraw = memorisation::withdraw,
                onDismissWipeOffer = memorisation::dismissWipeOffer,
                onWipe = onWipe,
            )
        }
        apps.state.collectAsStateWithLifecycle().value?.let {
            AppsSettingsSection(
                enabledFamilies = it.enabledFamilies,
                onToggleFamily = apps::toggleFamily,
                onOpenApps = openApps,
            )
        }
        data.state.collectAsStateWithLifecycle().value?.let {
            DataSettingsSection(
                state = it,
                zone = zone,
                onRetention = data::setRetention,
                onBudget = data::setBudget,
                onDeletePeriod = data::delete,
                onDeleteApp = data::delete,
                onMessageShown = data::messageShown,
                onWipe = onWipe,
            )
        }
        security()
        languages.state.collectAsStateWithLifecycle().value?.let {
            LanguageSettingsSection(
                state = it,
                onDownload = languages::download,
                onConfirmDownload = {
                    (context as? Activity)?.let { activity ->
                        languages.confirmDownload { bindings.confirmPackDownload(activity) }
                    }
                },
                onImport = languages::import,
                onUse = languages::askToUse,
                onConfirmUse = languages::confirmUse,
                onCancelUse = languages::cancelUse,
                onRemove = languages::remove,
                onErrorShown = languages::errorShown,
            )
        }
        history.state.collectAsStateWithLifecycle().value?.let {
            HistorySettingsSection(state = it, onEnabledChange = history::setEnabled, onClear = history::clear)
        }
        activation()
        val shareTitle = stringResource(SettingsR.string.settings_diagnostic_share_title)
        diagnostic.state.collectAsStateWithLifecycle().value?.let { state ->
            DiagnosticSettingsSection(
                state = state,
                onShare = {
                    val text = shareTitle + "\n" +
                        DiagnosticSettingsViewModel.report(state, BuildConfig.VERSION_NAME, Build.VERSION.SDK_INT)
                    // An explicit share by the user: counter names and numbers only (INV-06, INV-07).
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                    start(context, Intent.createChooser(send, shareTitle))
                },
            )
        }
        val licences = remember {
            context.resources.openRawResource(R.raw.third_party_licences).bufferedReader().use { it.readText() }
        }
        AboutSettingsSection(
            version = BuildConfig.VERSION_NAME,
            onPrivacyPolicy = { start(context, Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.PRIVACY_POLICY_URL))) },
            licences = licences,
        )
    }
}

/** The list of the installed applications (Settings → Applications). */
@Composable
fun AppsSettingsScreen(bindings: SettingsBindings) {
    val apps = viewModel(key = "settings-apps-list") { bindings.apps() }
    LifecycleResumeEffect(apps) {
        apps.refresh()
        onPauseOrDispose {}
    }
    val state by apps.state.collectAsStateWithLifecycle()
    state?.let {
        AppsListScreen(
            state = it,
            onQueryChange = apps::setQuery,
            onSetRemembered = apps::setRemembered,
            onConfirmInclude = apps::confirmInclude,
            onCancelInclude = apps::cancelInclude,
        )
    }
}

private fun start(context: android.content.Context, intent: Intent) {
    runCatching { context.startActivity(intent) }.onFailure { SmLog.w(TAG, "settings_intent_failed", it) }
}

private const val TAG = "Settings"
