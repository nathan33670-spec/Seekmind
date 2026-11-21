package fr.corsicajack.seekmind.di

import android.content.Context
import androidx.work.WorkManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import fr.corsicajack.seekmind.capture.ConnectedOcrEngine
import fr.corsicajack.seekmind.capture.ConnectedScreenshots
import fr.corsicajack.seekmind.core.common.Clock
import fr.corsicajack.seekmind.core.database.DatabaseState
import fr.corsicajack.seekmind.core.database.settings.SettingsRepository
import fr.corsicajack.seekmind.core.model.IngestResult
import fr.corsicajack.seekmind.core.model.PipelineEvent
import fr.corsicajack.seekmind.core.model.RawCapture
import fr.corsicajack.seekmind.core.model.config.CaptureConfig
import fr.corsicajack.seekmind.core.model.config.IndexConfig
import fr.corsicajack.seekmind.core.model.config.OcrConfig
import fr.corsicajack.seekmind.core.model.config.PrivacyConfig
import fr.corsicajack.seekmind.core.model.port.CaptureSink
import fr.corsicajack.seekmind.core.model.port.PrivacyGate
import fr.corsicajack.seekmind.core.model.port.ThermalGovernor
import fr.corsicajack.seekmind.engine.capture.StableScreenHandler
import fr.corsicajack.seekmind.engine.capture.meta.ActivityTracker
import fr.corsicajack.seekmind.engine.capture.meta.ScreenMetaReader
import fr.corsicajack.seekmind.engine.capture.pipeline.A11yScreenSource
import fr.corsicajack.seekmind.engine.capture.pipeline.CaptureGate
import fr.corsicajack.seekmind.engine.capture.pipeline.CapturePipeline
import fr.corsicajack.seekmind.engine.capture.pipeline.ConnectedService
import fr.corsicajack.seekmind.engine.capture.pipeline.MergedLines
import fr.corsicajack.seekmind.engine.capture.pipeline.ProbeScreenshotStep
import fr.corsicajack.seekmind.engine.capture.pipeline.ScreenshotStep
import fr.corsicajack.seekmind.engine.capture.routing.EventRouter
import fr.corsicajack.seekmind.engine.capture.status.CaptureStatusRepository
import fr.corsicajack.seekmind.engine.capture.tree.TreeExtractor
import fr.corsicajack.seekmind.engine.capture.url.BrowserProfiles
import fr.corsicajack.seekmind.engine.capture.url.UrlExtractor
import fr.corsicajack.seekmind.engine.capture.url.UrlTracker
import fr.corsicajack.seekmind.engine.index.EmbeddingScheduler as IngestScheduler
import fr.corsicajack.seekmind.engine.index.IngestService
import fr.corsicajack.seekmind.engine.index.SimHash
import fr.corsicajack.seekmind.engine.index.embedding.EmbeddingScheduler
import fr.corsicajack.seekmind.engine.ocr.policy.OcrPolicy
import fr.corsicajack.seekmind.engine.ocr.probe.ScreenshotSecureWindowProbe
import fr.corsicajack.seekmind.engine.ocr.recognition.MlKitOcrEngine
import fr.corsicajack.seekmind.engine.ocr.recognition.OcrMerge
import fr.corsicajack.seekmind.engine.privacy.DefaultPrivacyGate
import fr.corsicajack.seekmind.engine.privacy.UserChoicesCache
import fr.corsicajack.seekmind.engine.privacy.apps.AndroidPackageFacts
import fr.corsicajack.seekmind.engine.privacy.apps.AppFamilies
import fr.corsicajack.seekmind.engine.privacy.apps.FamilyLists
import fr.corsicajack.seekmind.engine.privacy.apps.SensitiveApps
import java.time.Instant
import java.time.ZoneId
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow

/**
 * The capture pipeline (docs/specs/capture.md §6, ticket M2-06), bound as the stable-screen handler of
 * the capture loop, step 6 included (secure-window probe and OCR, ticket M3-05).
 */
@Module
@InstallIn(SingletonComponent::class)
object PipelineModule {
    @Provides
    @Singleton
    fun browsers(@ApplicationContext context: Context): BrowserProfiles = BrowserProfiles.load(context)

    @Provides
    @Singleton
    fun sensitiveApps(@ApplicationContext context: Context): SensitiveApps = SensitiveApps.load(context)

    /** The families of applications (confidentialite §3.4), shared by the gate and the welcome flow. */
    @Provides
    @Singleton
    fun appFamilies(
        @ApplicationContext context: Context,
        browsers: BrowserProfiles,
        sensitive: SensitiveApps,
    ): AppFamilies = AppFamilies(AndroidPackageFacts(context), FamilyLists.load(context), browsers.packages, sensitive)

    /** The gate of the pipeline is the one Settings → Applications explains (DefaultPrivacyGate.statusOf). */
    @Provides
    @Singleton
    fun privacyGate(gate: DefaultPrivacyGate): PrivacyGate = gate

    @Provides
    @Singleton
    fun defaultPrivacyGate(
        @ApplicationContext context: Context,
        browsers: BrowserProfiles,
        settings: SettingsRepository,
        database: Provider<DatabaseState>,
        router: Provider<EventRouter>,
        @ApplicationScope scope: CoroutineScope,
        sensitive: SensitiveApps,
        families: AppFamilies,
    ): DefaultPrivacyGate {
        val facts = AndroidPackageFacts(context)
        // The rules live in the database, opened off the main thread on first use.
        val rules = flow {
            val ready = database.get() as? DatabaseState.Ready
            emitAll(ready?.database?.appRules?.observe() ?: emptyFlow())
        }
        val choices = UserChoicesCache(scope, rules, settings.enabledFamilies)
        return DefaultPrivacyGate(
            config = PrivacyConfig(),
            sensitive = sensitive,
            families = families,
            facts = facts,
            browserPackages = browsers.packages,
            ignoredPackages = { router.get().ignoredPackages() },
            choices = { choices.current },
        )
    }

    /**
     * Storage through the database once it is open; a capture before that, or with the database
     * unavailable, fails and is counted as an error (§6).
     */
    @Provides
    @Singleton
    fun captureSink(
        @ApplicationContext context: Context,
        database: Provider<DatabaseState>,
        clock: Clock,
        config: IndexConfig,
    ): CaptureSink {
        val scheduler = IngestScheduler {
            EmbeddingScheduler.scheduleCharging(WorkManager.getInstance(context), after = false)
        }
        return object : CaptureSink {
            private var service: IngestService? = null

            override suspend fun ingest(raw: RawCapture): IngestResult {
                val ingest = service ?: run {
                    val ready = checkNotNull(database.get() as? DatabaseState.Ready) { "database unavailable" }
                    IngestService(ready.database.captures, scheduler, clock, config).also { service = it }
                }
                return ingest.ingest(raw)
            }
        }
    }

    @Provides
    @Singleton
    fun pipeline(
        @ApplicationContext context: Context,
        config: CaptureConfig,
        clock: Clock,
        settings: SettingsRepository,
        connected: ConnectedService,
        activities: ActivityTracker,
        privacy: PrivacyGate,
        browsers: BrowserProfiles,
        sink: CaptureSink,
        database: Provider<DatabaseState>,
        status: CaptureStatusRepository,
        screenshot: ScreenshotStep,
    ): CapturePipeline {
        val urls = UrlExtractor(browsers)
        val tracker = UrlTracker(config, clock)
        return CapturePipeline(
            config = config,
            clock = clock,
            gate = {
                settings.current().let { CaptureGate(consented = it.hasCurrentConsent(), pausedUntil = it.pausedUntil) }
            },
            screens = A11yScreenSource(context, connected),
            metas = ScreenMetaReader(activities, clock),
            privacy = privacy,
            extractor = TreeExtractor(config, clock, browsers.pageTitleClasses),
            screenshot = screenshot,
            merger = { tree, ocr -> OcrMerge.merge(tree, ocr).let { MergedLines(it.lines, it.textSource) } },
            url = { packageName, root, pageTitle ->
                tracker.urlOf(packageName, urls.read(packageName, root), pageTitle)
            },
            simHash = SimHash::of,
            sink = sink,
        ) { event ->
            if (event is PipelineEvent.Stored) status.onCaptureStored(clock.nowMillis())
            val day = Instant.ofEpochMilli(clock.nowMillis()).atZone(ZoneId.systemDefault()).toLocalDate()
            (database.get() as? DatabaseState.Ready)?.database?.metrics?.increment(
                day.toString(),
                CapturePipeline.metricKey(event),
            )
        }
    }

    /**
     * Step 6: the OCR policy, then one screenshot for the secure-window probe and the OCR (ocr §3, §5).
     * The probe's cache lives in the database once it is open.
     */
    @Provides
    @Singleton
    fun screenshotStep(
        connected: ConnectedService,
        ocr: OcrConfig,
        clock: Clock,
        governor: ThermalGovernor,
        database: Provider<DatabaseState>,
    ): ScreenshotStep {
        val probe = ScreenshotSecureWindowProbe(
            shots = ConnectedScreenshots(connected, ocr, clock),
            ocr = ConnectedOcrEngine(connected, { MlKitOcrEngine(ocr) }, MlKitOcrEngine::close),
            store = { (database.get() as? DatabaseState.Ready)?.database?.secureProbes },
            config = ocr,
            clock = clock,
        )
        return ProbeScreenshotStep(probe, OcrPolicy(ocr, governor, clock))
    }

    @Provides
    @Singleton
    fun stableScreenHandler(pipeline: CapturePipeline): StableScreenHandler = pipeline
}
