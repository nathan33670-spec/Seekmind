package fr.corsicajack.seekmind.settings

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.corsicajack.seekmind.apps.PackageManagerAppLabels
import fr.corsicajack.seekmind.core.common.Clock
import fr.corsicajack.seekmind.core.common.SmLog
import fr.corsicajack.seekmind.core.database.DatabaseHandle
import fr.corsicajack.seekmind.core.database.DatabaseState
import fr.corsicajack.seekmind.core.database.settings.SettingsRepository
import fr.corsicajack.seekmind.core.database.store.AppRule
import fr.corsicajack.seekmind.core.database.store.AppRuleMode
import fr.corsicajack.seekmind.engine.capture.pause.PauseController
import fr.corsicajack.seekmind.engine.capture.pause.PauseDuration
import fr.corsicajack.seekmind.engine.capture.status.CaptureStatusRepository
import fr.corsicajack.seekmind.engine.embedding.catalog.DefaultModelCatalog
import fr.corsicajack.seekmind.engine.embedding.catalog.FileModelPackInstaller
import fr.corsicajack.seekmind.engine.embedding.catalog.ModelPackInstaller
import fr.corsicajack.seekmind.engine.embedding.catalog.PlayModelPackInstaller
import fr.corsicajack.seekmind.engine.privacy.DefaultPrivacyGate
import fr.corsicajack.seekmind.feature.settings.AppsSettingsViewModel
import fr.corsicajack.seekmind.feature.settings.DataSettingsViewModel
import fr.corsicajack.seekmind.feature.settings.DiagnosticSettingsViewModel
import fr.corsicajack.seekmind.feature.settings.HistorySettingsViewModel
import fr.corsicajack.seekmind.feature.settings.InstalledApp
import fr.corsicajack.seekmind.feature.settings.LanguageSettingsViewModel
import fr.corsicajack.seekmind.feature.settings.MemorisationSettingsViewModel
import fr.corsicajack.seekmind.feature.settings.PauseChoice
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow

/**
 * What the sections of Settings (ticket M6-06) are built from: each factory gives its view model the
 * stores and engines it needs, as plain functions. The database is opened off the main thread on first use;
 * until then, a section shows nothing or does nothing.
 */
class SettingsBindings @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val status: CaptureStatusRepository,
    private val pause: PauseController,
    private val database: Provider<DatabaseState>,
    private val gate: DefaultPrivacyGate,
    private val catalog: Deferred<DefaultModelCatalog>,
    private val installer: ModelPackInstaller,
    private val file: FileModelPackInstaller,
    private val clock: Clock,
) {
    private val labels = PackageManagerAppLabels(context.packageManager)

    private fun db(): DatabaseHandle? = (database.get() as? DatabaseState.Ready)?.database

    fun memorisation() = MemorisationSettingsViewModel(
        status = status.status,
        settings = settings.settings,
        pause = { choice ->
            pause.pause(
                when (choice) {
                    PauseChoice.ONE_HOUR -> PauseDuration.ONE_HOUR
                    PauseChoice.UNTIL_TOMORROW_MORNING -> PauseDuration.UNTIL_TOMORROW_MORNING
                    PauseChoice.INDEFINITELY -> PauseDuration.INDEFINITELY
                },
            )
        },
        resume = pause::resume,
        withdrawConsent = settings::withdrawConsent,
    )

    fun apps() = AppsSettingsViewModel(
        settings = settings.settings,
        rules = flow { emitAll(db()?.appRules?.observe() ?: emptyFlow()) },
        installed = ::launchableApps,
        statusOf = gate::statusOf,
        setFamilies = settings::setEnabledFamilies,
        setRule = { packageName, mode -> setRule(packageName, mode) },
        io = Dispatchers.IO,
    )

    fun data() = DataSettingsViewModel(
        settings = settings.settings,
        stats = { db()?.captures?.stats() },
        appsWithCaptures = {
            db()?.captures?.packages().orEmpty().map { InstalledApp(it, labels.label(it)) }.sortedBy { it.label }
        },
        setRetentionDays = settings::setRetentionDays,
        setBudgetBytes = settings::setStorageBudgetBytes,
        deleteRange = { from, to -> db()?.captures?.deleteByRange(from, to) },
        deletePackage = { db()?.captures?.deleteByPackage(it) },
        now = clock::nowMillis,
        io = Dispatchers.IO,
    )

    fun languages() = LanguageSettingsViewModel(
        packs = { CatalogLanguagePacks(catalog.await(), installer, file, context.contentResolver) },
        pendingFragments = { db()?.vectors?.pendingCount() ?: 0 },
    )

    fun history() = HistorySettingsViewModel(
        settings = settings.settings,
        setEnabled = settings::setHistoryEnabled,
        clear = { db()?.searchHistory?.clear() },
    )

    fun diagnostic() = DiagnosticSettingsViewModel(
        metrics = { from, to -> db()?.metrics?.range(from.toString(), to.toString()).orEmpty() },
        pendingFragments = { db()?.vectors?.pendingCount() ?: 0 },
        today = LocalDate::now,
        io = Dispatchers.IO,
    )

    /** Google Play's confirmation of a pack download; false when packs do not come from Play. */
    suspend fun confirmPackDownload(activity: android.app.Activity): Boolean =
        (installer as? PlayModelPackInstaller)?.requestConfirmation(activity) ?: false

    /**
     * « Télécharger » at the end of the welcome flow: a Play download, on the user's action (§5.4). The request
     * starts it; its progress, and Play's confirmation when one is needed, are on Settings → Langues, which the
     * flow opens next. A failure is counted, never thrown: the user can try again from there.
     */
    suspend fun downloadMultilingualPack() {
        if (installer !is PlayModelPackInstaller) return
        try {
            catalog.await().install(MULTI)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            SmLog.w(TAG, "pack_download_failed", failure)
        }
    }

    private suspend fun setRule(packageName: String, mode: AppRuleMode?) {
        val rules = db()?.appRules ?: return
        if (mode == null) rules.remove(packageName) else rules.set(AppRule(packageName, mode, clock.nowMillis()))
    }

    /** The launchable applications but SeekMind itself; labels only. */
    private fun launchableApps(): List<InstalledApp> {
        val packageManager = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(launcher, PackageManager.MATCH_ALL)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != context.packageName }
            .map { InstalledApp(it, labels.label(it)) }
    }

    private companion object {
        const val MULTI = "multi"
        const val TAG = "SettingsBindings"
    }
}
