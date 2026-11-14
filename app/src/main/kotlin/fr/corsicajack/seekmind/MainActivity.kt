package fr.corsicajack.seekmind

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Process
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
import fr.corsicajack.seekmind.core.model.port.EntitlementGate
import fr.corsicajack.seekmind.di.ApplicationScope
import fr.corsicajack.seekmind.feature.journal.JournalRoute
import fr.corsicajack.seekmind.feature.journal.JournalViewModel
import fr.corsicajack.seekmind.feature.search.SearchRoute
import fr.corsicajack.seekmind.feature.search.SearchViewModel
import fr.corsicajack.seekmind.feature.search.detail.CaptureDetailRoute
import fr.corsicajack.seekmind.feature.search.detail.CaptureDetailViewModel
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

    /** Search terms of the result opened last, highlighted in its detail; in memory only (INV-06). */
    private var openedTerms: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            SeekMindTheme {
                SeekMindRoot(
                    searchContent = { open -> SearchContent(open) },
                    journalContent = { open -> JournalContent(open) },
                    captureContent = { id, close -> CaptureContent(id, close) },
                )
            }
        }
    }

    @Composable
    private fun SearchContent(openCapture: (Long) -> Unit) {
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
            onOpenSource = { hit -> openSource(hit.url, hit.packageName) },
            onReset = ::resetEverything,
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
                io = Dispatchers.IO,
                onDeleted = deletions::publish,
            )
        }
        CaptureDetailRoute(
            viewModel = detail,
            zone = ZoneId.systemDefault(),
            onOpenSource = { capture -> openSource(capture.url, capture.packageName) },
            onOpenLink = { link -> openSource(link, packageName = null) },
            onClose = close,
        )
    }

    /**
     * Until the return to the source (M7): the page of an http(s) URL, otherwise the application. Only
     * http(s) URLs leave SeekMind, to another application (INV-12).
     */
    private fun openSource(url: String?, packageName: String?) {
        val page = url?.let(Uri::parse)?.takeIf { it.scheme == "https" || it.scheme == "http" }
        val intent = if (page != null) {
            Intent(Intent.ACTION_VIEW, page).addCategory(Intent.CATEGORY_BROWSABLE)
        } else {
            packageName?.let(packageManager::getLaunchIntentForPackage)
        } ?: return
        runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { SmLog.w(TAG, "open_source_failed", it) }
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
