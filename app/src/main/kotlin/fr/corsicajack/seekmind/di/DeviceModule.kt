package fr.corsicajack.seekmind.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import fr.corsicajack.seekmind.core.model.config.EmbeddingConfig
import fr.corsicajack.seekmind.core.model.config.IndexConfig
import fr.corsicajack.seekmind.core.model.config.OcrConfig
import fr.corsicajack.seekmind.core.model.port.ThermalGovernor
import fr.corsicajack.seekmind.engine.capture.url.BrowserProfiles
import fr.corsicajack.seekmind.engine.ocr.policy.AndroidDeviceSignals
import fr.corsicajack.seekmind.engine.ocr.policy.GovernorRules
import fr.corsicajack.seekmind.engine.ocr.policy.SignalThermalGovernor
import fr.corsicajack.seekmind.engine.ocr.probe.EphemeralMessaging
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope

/**
 * The thermal and energy governor of the process (docs/specs/ocr.md §6), shared by the OCR and the
 * embedding batches. Its signals live as long as the process: the receivers are registered once, on
 * the application context.
 */
@Module
@InstallIn(SingletonComponent::class)
object DeviceModule {
    @Provides
    @Singleton
    fun ocrConfig(@ApplicationContext context: Context): OcrConfig =
        OcrConfig(probeAlwaysPackages = EphemeralMessaging.probeAlways(BrowserProfiles.load(context).packages))

    @Provides
    @Singleton
    fun indexConfig(): IndexConfig = IndexConfig()

    @Provides
    @Singleton
    fun governor(
        @ApplicationContext context: Context,
        ocr: OcrConfig,
        embedding: EmbeddingConfig,
        @ApplicationScope scope: CoroutineScope,
    ): ThermalGovernor {
        val signals = AndroidDeviceSignals(context, context.mainExecutor)
        signals.start()
        return SignalThermalGovernor(signals.state, GovernorRules(ocr, embedding), scope)
    }
}
