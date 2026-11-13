package fr.corsicajack.seekmind.di

import android.content.Context
import dagger.BindsOptionalOf
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import fr.corsicajack.seekmind.core.common.Clock
import fr.corsicajack.seekmind.core.database.settings.SettingsRepository
import fr.corsicajack.seekmind.core.model.config.CaptureConfig
import fr.corsicajack.seekmind.engine.capture.StableScreenHandler
import fr.corsicajack.seekmind.engine.capture.meta.ActivityTracker
import fr.corsicajack.seekmind.engine.capture.meta.PackageManagerActivityLookup
import fr.corsicajack.seekmind.engine.capture.pause.PauseController
import fr.corsicajack.seekmind.engine.capture.routing.AndroidCaptureEnvironment
import fr.corsicajack.seekmind.engine.capture.routing.CaptureLoop
import fr.corsicajack.seekmind.engine.capture.routing.CaptureThread
import fr.corsicajack.seekmind.engine.capture.routing.EventRouter
import fr.corsicajack.seekmind.engine.capture.routing.IgnoredPackages
import fr.corsicajack.seekmind.engine.capture.status.AccessibilityServiceState
import fr.corsicajack.seekmind.engine.capture.status.CaptureStatusRepository
import fr.corsicajack.seekmind.engine.capture.status.ServiceConnection
import java.time.ZoneId
import java.util.Optional
import javax.inject.Singleton

/** The capture service's graph (docs/specs/capture.md §2, §8). */
@Module
@InstallIn(SingletonComponent::class)
object CaptureModule {
    @Provides
    @Singleton
    fun captureConfig(): CaptureConfig = CaptureConfig()

    @Provides
    @Singleton
    fun captureThread(clock: Clock): CaptureThread = CaptureThread(clock)

    @Provides
    @Singleton
    fun connection(): ServiceConnection = ServiceConnection()

    @Provides
    @Singleton
    fun captureLoop(
        config: CaptureConfig,
        clock: Clock,
        thread: CaptureThread,
        @ApplicationContext context: Context,
        handler: Optional<StableScreenHandler>,
    ): CaptureLoop = CaptureLoop(
        config,
        clock,
        thread,
        ActivityTracker(PackageManagerActivityLookup(context.packageManager)),
        handler.orElse(null),
    )

    @Provides
    @Singleton
    fun router(
        thread: CaptureThread,
        loop: CaptureLoop,
        @ApplicationContext context: Context,
        settings: SettingsRepository,
        connection: ServiceConnection,
        clock: Clock,
    ): EventRouter = EventRouter(
        thread,
        loop,
        AndroidCaptureEnvironment(thread, IgnoredPackages(context), settings, connection),
        clock,
    )

    @Provides
    @Singleton
    fun status(
        @ApplicationContext context: Context,
        connection: ServiceConnection,
        settings: SettingsRepository,
        clock: Clock,
    ): CaptureStatusRepository =
        CaptureStatusRepository(AccessibilityServiceState(context, connection).enabled, settings.settings, clock)

    @Provides
    @Singleton
    fun pause(settings: SettingsRepository, clock: Clock): PauseController =
        PauseController(settings, clock, zone = ZoneId::systemDefault)

    /** The pipeline binds it (M2-06); until then a stable screen reads nothing. */
    @Module
    @InstallIn(SingletonComponent::class)
    interface Bindings {
        @BindsOptionalOf
        fun stableScreenHandler(): StableScreenHandler
    }
}
