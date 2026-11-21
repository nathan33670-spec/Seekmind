package fr.corsicajack.seekmind

import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import fr.corsicajack.seekmind.apps.PackageManagerAppLabels
import fr.corsicajack.seekmind.capture.CaptureDeletions
import fr.corsicajack.seekmind.capture.DeletableCaptures
import fr.corsicajack.seekmind.core.common.SmLog
import fr.corsicajack.seekmind.core.database.DatabaseState
import fr.corsicajack.seekmind.core.database.WipeService
import fr.corsicajack.seekmind.core.database.settings.SettingsRepository
import fr.corsicajack.seekmind.core.designsystem.theme.SeekMindTheme
import fr.corsicajack.seekmind.core.model.AppFamily
import fr.corsicajack.seekmind.core.model.CaptureStatus
import fr.corsicajack.seekmind.core.model.Entitlement
import fr.corsicajack.seekmind.core.model.SourceRef
import fr.corsicajack.seekmind.core.model.config.MonetizationConfig
import fr.corsicajack.seekmind.core.model.port.EntitlementGate
import fr.corsicajack.seekmind.core.model.port.RewardProvider
import fr.corsicajack.seekmind.di.ApplicationScope
import fr.corsicajack.seekmind.engine.capture.pause.PauseController
import fr.corsicajack.seekmind.engine.capture.status.CaptureStatusRepository
import fr.corsicajack.seekmind.engine.monetization.RewardFlow
import fr.corsicajack.seekmind.engine.monetization.license.LicenseActivator
import fr.corsicajack.seekmind.engine.privacy.apps.AppFamilies
import fr.corsicajack.seekmind.engine.source.LaunchResult
import fr.corsicajack.seekmind.engine.source.SourceLinks
import fr.corsicajack.seekmind.engine.source.SourceReturn
import fr.corsicajack.seekmind.feature.journal.JournalRoute
import fr.corsicajack.seekmind.feature.journal.JournalViewModel
import fr.corsicajack.seekmind.feature.onboarding.FamilyApp
import fr.corsicajack.seekmind.feature.onboarding.OnboardingRoute
import fr.corsicajack.seekmind.feature.onboarding.OnboardingStart
import fr.corsicajack.seekmind.feature.onboarding.OnboardingViewModel
import fr.corsicajack.seekmind.feature.paywall.CreditsSheet
import fr.corsicajack.seekmind.feature.paywall.CreditsSheetViewModel
import fr.corsicajack.seekmind.feature.search.SearchRoute
import fr.corsicajack.seekmind.feature.search.SearchViewModel
import fr.corsicajack.seekmind.feature.search.StatusAction
import fr.corsicajack.seekmind.feature.search.detail.CaptureDetailRoute
import fr.corsicajack.seekmind.feature.search.detail.CaptureDetailViewModel
import fr.corsicajack.seekmind.feature.settings.ActivationSettingsRoute
import fr.corsicajack.seekmind.feature.settings.ActivationSettingsViewModel
import fr.corsicajack.seekmind.feature.settings.SecuritySettingsRoute
import fr.corsicajack.seekmind.feature.settings.SecuritySettingsViewModel
import fr.corsicajack.seekmind.feature.settings.help.ServiceHelpScreen
import fr.corsicajack.seekmind.lock.AppLock
import fr.corsicajack.seekmind.lock.BiometricAuthenticator
import fr.corsicajack.seekmind.lock.LockGate
import fr.corsicajack.seekmind.lock.LockState
import fr.corsicajack.seekmind.navigation.TopLevelDestination
import fr.corsicajack.seekmind.navigation.navigateToTopLevel
import fr.corsicajack.seekmind.onboarding.InstalledFamilyApps
import fr.corsicajack.seekmind.search.SearchStack
import fr.corsicajack.seekmind.settings.AppsSettingsScreen
import fr.corsicajack.seekmind.settings.SettingsBindings
import fr.corsicajack.seekmind.settings.SettingsScreen
import fr.corsicajack.seekmind.ui.SeekMindRoot
import java.time.LocalDate
import java.time.ZoneId
import java.util.Optional
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Launcher activity, host of every screen. Its window is secure ([SecureActivity]) and its content
 * is composed only once the application lock allows it ([AppLock]).
 */
@AndroidEntryPoint
class MainActivity : SecureActivity() {
    @Inject lateinit var appLock: AppLock

    @Inject lateinit var settings: SettingsRepository

    /** Opened on first use, off the main thread. */
    @Inject lateinit var database: Provider<DatabaseState>

    @Inject @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    @Inject lateinit var search: SearchStack

    @Inject lateinit var entitlements: EntitlementGate

    @Inject lateinit var activator: dagger.Lazy<LicenseActivator>

    @Inject lateinit var rewardFlow: RewardFlow

    /** `NoRewardProvider` in the `offline` variant: no ad privacy choices to offer (RewardModule). */
    @Inject lateinit var rewardProvider: Optional<RewardProvider>

    @Inject lateinit var monetizationConfig: MonetizationConfig

    @Inject lateinit var wipe: WipeService

    @Inject lateinit var deletions: CaptureDeletions

    /** The capture store of the screens that delete: the ingestion forgets what it learnt afterwards. */
    @Inject lateinit var deletableCaptures: DeletableCaptures

    @Inject lateinit var families: AppFamilies

    /** The welcome flow asked for from the banner; null otherwise. */
    private var onboarding by mutableStateOf<OnboardingStart?>(null)

    @Inject lateinit var captureStatus: CaptureStatusRepository

    @Inject lateinit var pause: PauseController

    @Inject lateinit var settingsBindings: SettingsBindings

    /** Resolved off the main thread on first use: reading the rules is not done at start-up. */
    @Inject lateinit var sourceReturn: dagger.Lazy<SourceReturn>

    /** Search terms of the result opened last, highlighted in its detail; in memory only (INV-06). */
    private var openedTerms: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            SeekMindTheme {
                val lockState by appLock.state.collectAsStateWithLifecycle(initialValue = LockState.PENDING)
                // Outside the gate, so that the screens come back as they were after an unlock.
                val navController = rememberNavController()
                val current by settings.settings.collectAsStateWithLifecycle(initialValue = null)
                LockGate(
                    state = lockState,
                    onUnlock = { BiometricAuthenticator.authenticate(this, appLock::onAuthenticated) },
                ) {
                    val loaded = current ?: return@LockGate
                    // The welcome flow at the first start, or the disclosure reopened from the banner.
                    val start = onboarding ?: if (!loaded.onboardingDone) OnboardingStart.WELCOME else null
                    if (start != null) {
                        OnboardingContent(
                            start = start,
                            onDownloadPack = {
                                // The download is the user's « Télécharger » (docs/specs/embeddings.md §5.4).
                                applicationScope.launch { settingsBindings.downloadMultilingualPack() }
                                navController.navigateToTopLevel(TopLevelDestination.SETTINGS)
                            },
                        ) { onboarding = null }
                        return@LockGate
                    }
                    SeekMindRoot(
                        navController = navController,
                        searchContent = { open, help ->
                            SearchContent(open, help) { navController.navigateToTopLevel(TopLevelDestination.SETTINGS) }
                        },
                        journalContent = { open -> JournalContent(open) },
                        captureContent = { id, close -> CaptureContent(id, close) },
                        helpContent = { ServiceHelpScreen() },
                        settingsContent = { openApps -> SettingsContent(openApps) },
                        appsContent = { AppsSettingsScreen(settingsBindings) },
                    )
                }
            }
        }
    }

    @Composable
    private fun SearchContent(openCapture: (Long) -> Unit, openHelp: () -> Unit, openSettings: () -> Unit) {
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
                liveSearchDebounceMs = monetizationConfig.liveSearchDebounceMs,
            )
        }
        // The credits sheet (docs/specs/monetisation.md §6): its ad is prepared when it opens, not before.
        var sheet by rememberSaveable { mutableStateOf(false) }
        val credits = viewModel(key = "credits-sheet") { CreditsSheetViewModel(rewardFlow) }
        if (sheet) {
            val sheetState by credits.state.collectAsStateWithLifecycle()
            LaunchedEffect(credits) { credits.open(this@MainActivity) }
            LaunchedEffect(sheetState.credited) {
                if (sheetState.credited) {
                    sheet = false
                    credits.close()
                    searchViewModel.onCredited()
                }
            }
            CreditsSheet(
                state = sheetState,
                dailyFreeCredits = monetizationConfig.dailyFreeCredits,
                onWatch = { credits.watch(this@MainActivity) },
                onCode = {
                    sheet = false
                    credits.close()
                    openSettings()
                },
                onDismiss = {
                    sheet = false
                    credits.close()
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

                    // « Activer la mémorisation »: the disclosure again (EF-ONB-04).
                    StatusAction.ENABLE -> onboarding = OnboardingStart.DISCLOSURE
                }
            },
            canEnableCapture = true,
            onCredits = { if (entitlements.state.value is Entitlement.Free) sheet = true },
        )
    }

    @Composable
    private fun CaptureContent(captureId: Long, close: () -> Unit) {
        val terms = openedTerms
        val detail = viewModel(key = "capture-$captureId") {
            CaptureDetailViewModel(
                captureId = captureId,
                terms = terms,
                store = deletableCaptures::store,
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
        appLock.onForeground()
        search.onForeground()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            appLock.onBackground()
            search.onBackground()
        }
    }

    /** The welcome flow (docs/specs/ui-ux.md §3.1); [onDone] when it ends, accepted or refused. */
    @Composable
    private fun OnboardingContent(start: OnboardingStart, onDownloadPack: () -> Unit, onDone: () -> Unit) {
        var help by rememberSaveable { mutableStateOf(false) }
        val model = viewModel(key = "onboarding-$start") {
            OnboardingViewModel(
                settings = settings,
                start = start,
                serviceEnabled = captureStatus.status.map { it is CaptureStatus.Active || it is CaptureStatus.Paused },
                isDeviceSecure = appLock::isDeviceSecure,
                now = System::currentTimeMillis,
                offerLanguagePack = start == OnboardingStart.WELCOME && noFrenchLocale(),
            )
        }
        val apps by produceState(emptyMap<AppFamily, List<FamilyApp>>()) {
            value = withContext(Dispatchers.IO) { InstalledFamilyApps(packageManager, families).load() }
        }
        if (help) {
            BackHandler { help = false }
            ServiceHelpScreen()
            return
        }
        OnboardingRoute(
            viewModel = model,
            familyApps = apps,
            onOpenAccessibilitySettings = ::openAccessibilitySettings,
            onHelp = { help = true },
            onFinished = { onDone() },
            onDownloadPack = onDownloadPack,
        )
    }

    @Composable
    private fun JournalContent(openCapture: (Long) -> Unit) {
        val journal = viewModel {
            JournalViewModel(
                store = deletableCaptures::store,
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

    @Composable
    private fun SettingsContent(openApps: () -> Unit) {
        val security = viewModel {
            SecuritySettingsViewModel(settings, appLock::isDeviceSecure, appLock::onAuthenticated)
        }
        val activation = viewModel {
            ActivationSettingsViewModel(
                entitlement = entitlements.state,
                activate = { code -> withContext(Dispatchers.IO) { activator.get().activate(code) } },
                deactivate = { withContext(Dispatchers.IO) { activator.get().deactivate() } },
            )
        }
        SettingsScreen(
            bindings = settingsBindings,
            openApps = openApps,
            onReadDisclosure = { onboarding = OnboardingStart.DISCLOSURE },
            onActivate = ::openAccessibilitySettings,
            onWipe = ::resetEverything,
            security = { SecuritySettingsRoute(viewModel = security) },
            activation = {
                val privacy = rewardProvider.orElse(null)
                val privacyRequired by (privacy?.privacyOptionsRequired ?: remember { MutableStateFlow(false) })
                    .collectAsStateWithLifecycle()
                val scope = rememberCoroutineScope()
                ActivationSettingsRoute(
                    viewModel = activation,
                    privacyOptionsRequired = privacyRequired,
                    onPrivacyOptions = { scope.launch { privacy?.showPrivacyOptions(this@MainActivity) } },
                )
            },
        )
    }

    /** No language of the phone is French: the multilingual pack is offered (docs/specs/embeddings.md §5.4). */
    private fun noFrenchLocale(): Boolean {
        val locales = android.content.res.Resources.getSystem().configuration.locales
        return (0 until locales.size()).none { locales[it].language == "fr" }
    }

    private companion object {
        const val TAG = "MainActivity"
    }
}
