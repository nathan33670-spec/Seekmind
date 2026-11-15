package fr.corsicajack.seekmind

import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dagger.hilt.android.AndroidEntryPoint
import fr.corsicajack.seekmind.apps.PackageManagerAppLabels
import fr.corsicajack.seekmind.capture.CaptureDeletions
import fr.corsicajack.seekmind.core.common.SmLog
import fr.corsicajack.seekmind.core.database.DatabaseState
import fr.corsicajack.seekmind.core.database.WipeService
import fr.corsicajack.seekmind.core.database.settings.SettingsRepository
import fr.corsicajack.seekmind.core.designsystem.theme.SeekMindTheme
import fr.corsicajack.seekmind.core.model.SourceRef
import fr.corsicajack.seekmind.core.model.port.EntitlementGate
import fr.corsicajack.seekmind.di.ApplicationScope
import fr.corsicajack.seekmind.engine.capture.pause.PauseController
import fr.corsicajack.seekmind.engine.capture.status.CaptureStatusRepository
import fr.corsicajack.seekmind.engine.source.LaunchResult
import fr.corsicajack.seekmind.engine.source.SourceLinks
import fr.corsicajack.seekmind.engine.source.SourceReturn
import fr.corsicajack.seekmind.feature.journal.JournalRoute
import fr.corsicajack.seekmind.feature.journal.JournalViewModel
import fr.corsicajack.seekmind.feature.search.SearchRoute
import fr.corsicajack.seekmind.feature.search.SearchViewModel
import fr.corsicajack.seekmind.feature.search.StatusAction
import fr.corsicajack.seekmind.feature.search.detail.CaptureDetailRoute
import fr.corsicajack.seekmind.feature.search.detail.CaptureDetailViewModel
import fr.corsicajack.seekmind.feature.settings.help.ServiceHelpScreen
import fr.corsicajack.seekmind.search.SearchStack
import fr.corsicajack.seekmind.ui.SeekMindRoot
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Launcher activity, host of every screen. Its window is secure (EF-PRIV-11): it shows remembered
 * content, so it is kept out of screenshots, screen recordings and the recent apps thumbnail.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    /** Opened on first use, off the main thread. */
    @Inject lateinit var database: Provider<DatabaseState>

    @Inject @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    @Inject lateinit var search: SearchStack

    @Inject lateinit var entitlements: EntitlementGate

    @Inject lateinit var settings: SettingsRepository

    @Inject lateinit var wipe: WipeService

    @Inject lateinit var deletions: CaptureDeletions

    @Inject lateinit var captureStatus: CaptureStatusRepository

    @Inject lateinit var pause: PauseController

    /** Resolved off the main thread on first use: reading the rules is not done at start-up. */
    @Inject lateinit var sourceReturn: dagger.Lazy<SourceReturn>

    /** Search terms of the result opened last, highlighted in its detail; in memory only (INV-06). */
    private var openedTerms: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            SeekMindTheme {
                SeekMindRoot(
                    searchContent = { open, help -> SearchContent(open, help) },
                    journalContent = { open -> JournalContent(open) },
                    captureContent = { id, close -> CaptureContent(id, close) },
                    helpContent = { ServiceHelpScreen() },
                )
            }
        }
    }

    @Composable
    private fun SearchContent(openCapture: (Long) -> Unit, openHelp: () -> Unit) {
        val labels = PackageManagerAppLabels(packageManager)
        val searchViewModel = viewModel {
            SearchViewModel(
                engine = { search.engine() },
                gate = entitlements,
                history = { (database.get() as? DatabaseState.Ready)?.database?.searchHistory },
                historyEnabled = settings.settings.map { it.historyEnabled }.distinctUntilChanged(),
                pendingVectors = { (database.get() as? DatabaseState.Ready)?.database?.vectors?.pendingCount() ?: 0 },
                indexedApps = { (database.get() as? DatabaseState.Ready)?.database?.captures?.packages().orEmpty() },
                labels = labels,
                now = System::currentTimeMillis,
                zone = ZoneId::systemDefault,
                io = Dispatchers.IO,
                deletions = deletions.events,
                captureStatus = captureStatus.status,
                resolveSource = { hit ->
                    val captures = (database.get() as? DatabaseState.Ready)?.database?.captures
                    captures?.get(hit.captureId)?.let(sourceReturn.get().resolver::resolve)
                },
            )
        }
        val state by searchViewModel.state.collectAsStateWithLifecycle()
        SearchRoute(
            viewModel = searchViewModel,
            zone = ZoneId.systemDefault(),
            now = System::currentTimeMillis,
            onOpenResult = { hit ->
                openedTerms = state.terms
                openCapture(hit.captureId)
            },
            onOpenSource = ::openSource,
            onReset = ::resetEverything,
            onStatusAction = { action ->
                when (action) {
                    StatusAction.RESUME -> applicationScope.launch { pause.resume() }

                    StatusAction.REACTIVATE -> openAccessibilitySettings()

                    StatusAction.HELP -> openHelp()

                    // The disclosure arrives with M6-02; until then the banner offers no button.
                    StatusAction.ENABLE -> Unit
                }
            },
        )
    }

    @Composable
    private fun CaptureContent(captureId: Long, close: () -> Unit) {
        val terms = openedTerms
        val detail = viewModel(key = "capture-$captureId") {
            CaptureDetailViewModel(
                captureId = captureId,
                terms = terms,
                store = { (database.get() as? DatabaseState.Ready)?.database?.captures },
                labels = PackageManagerAppLabels(packageManager),
                resolver = sourceReturn.get().resolver,
                io = Dispatchers.IO,
                onDeleted = deletions::publish,
            )
        }
        CaptureDetailRoute(
            viewModel = detail,
            zone = ZoneId.systemDefault(),
            onOpenSource = ::openSource,
            onOpenLink = { link -> SourceLinks.refFor(link)?.let(::openSource) },
            onClose = close,
        )
    }

    /**
     * Opens the way back chosen by the user (docs/specs/retour-source.md §5): the launcher falls back to
     * the next level on failure and never throws; only admitted schemes leave SeekMind (INV-12).
     */
    private fun openSource(ref: SourceRef) {
        val result = sourceReturn.get().launcher.launch(this, ref)
        if (result == LaunchResult.Failed) SmLog.w(TAG, "open_source_failed", null, "level" to ref.level.ordinal)
    }

    /**
     * « Réactiver » (docs/specs/ui-ux.md §3.2): the system accessibility settings, where the user turns the
     * service back on. Only offered once consent is given (the banner shows « Service désactivé » after it).
     */
    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }.onFailure { SmLog.w(TAG, "accessibility_settings_failed", it) }
    }

    /** « Réinitialiser SeekMind » (ENF-REL-02): erase, then close; the next start creates a new database. */
    private fun resetEverything() {
        applicationScope.launch {
            wipe.wipeEverything()
            search.reset()
            withContext(Dispatchers.Main) { finishAndRemoveTask() }
            Process.killProcess(Process.myPid())
        }
    }

    override fun onStart() {
        super.onStart()
        search.onForeground()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) search.onBackground()
    }

    @Composable
    private fun JournalContent(openCapture: (Long) -> Unit) {
        val journal = viewModel {
            JournalViewModel(
                store = { (database.get() as? DatabaseState.Ready)?.database?.captures },
                labels = PackageManagerAppLabels(packageManager),
                zone = ZoneId::systemDefault,
                io = Dispatchers.IO,
                commitScope = applicationScope,
                deletions = deletions.events,
                onDeleted = deletions::publish,
            )
        }
        JournalRoute(
            viewModel = journal,
            zone = ZoneId.systemDefault(),
            today = { LocalDate.now() },
            onOpen = { id ->
                openedTerms = emptyList()
                openCapture(id)
            },
        )
    }

    private companion object {
        const val TAG = "MainActivity"
    }
}
