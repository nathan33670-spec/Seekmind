package fr.corsicajack.seekmind.di

import android.content.Context
import android.content.pm.PackageManager
import androidx.work.WorkManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import fr.corsicajack.seekmind.core.common.DefaultDispatcherProvider
import fr.corsicajack.seekmind.core.database.DatabaseState
import fr.corsicajack.seekmind.core.database.settings.SettingsRepository
import fr.corsicajack.seekmind.core.model.config.EmbeddingConfig
import fr.corsicajack.seekmind.engine.embedding.catalog.ActiveModelSetting
import fr.corsicajack.seekmind.engine.embedding.catalog.DefaultModelCatalog
import fr.corsicajack.seekmind.engine.embedding.catalog.FileModelPackInstaller
import fr.corsicajack.seekmind.engine.embedding.catalog.ModelPackInstaller
import fr.corsicajack.seekmind.engine.embedding.catalog.PackLock
import fr.corsicajack.seekmind.engine.embedding.catalog.PlayAssetPackGateway
import fr.corsicajack.seekmind.engine.embedding.catalog.PlayModelPackInstaller
import fr.corsicajack.seekmind.engine.embedding.engine.SharedEmbeddingEngineProvider
import fr.corsicajack.seekmind.engine.index.embedding.EmbeddingScheduler
import java.io.File
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/**
 * The catalogue of the embedding models (docs/specs/embeddings.md §5), created once per process off the
 * main thread. Packs come from Google Play when Play installed the application, else from a `.smpack`
 * file (§5.2).
 */
@Module
@InstallIn(SingletonComponent::class)
object ModelModule {
    @Provides
    @Singleton
    fun packInstaller(@ApplicationContext context: Context): FileModelPackInstaller = FileModelPackInstaller(
        root = File(context.noBackupFilesDir, "packs"),
        expected = { PackLock.byModel[it.id] },
        io = DefaultDispatcherProvider.io,
    )

    @Provides
    @Singleton
    fun installer(@ApplicationContext context: Context, file: FileModelPackInstaller): ModelPackInstaller =
        if (PlayAssetPackGateway.answers(installerOf(context))) {
            PlayModelPackInstaller(PlayAssetPackGateway(context))
        } else {
            file
        }

    private fun installerOf(context: Context): String? = try {
        context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    /**
     * Activation (§5.3): the engine session is closed, so that the next one loads the new model, and the
     * vectors are emptied, so that the embedding batches compute them again; the work on charge is planned
     * at once, if none is.
     */
    @Provides
    @Singleton
    fun catalog(
        @ApplicationContext context: Context,
        installer: ModelPackInstaller,
        settings: SettingsRepository,
        embeddings: Provider<SharedEmbeddingEngineProvider>,
        database: Provider<DatabaseState>,
        config: EmbeddingConfig,
        @ApplicationScope scope: CoroutineScope,
    ): Deferred<DefaultModelCatalog> = scope.async(DefaultDispatcherProvider.io) {
        DefaultModelCatalog.create(
            installer = installer,
            io = DefaultDispatcherProvider.io,
            setting = object : ActiveModelSetting {
                override suspend fun get() = settings.current().activeModelId

                override suspend fun set(id: String) = settings.setActiveModelId(id)
            },
        ) { spec ->
            embeddings.get().release()
            (database.get() as? DatabaseState.Ready)?.database?.vectors?.let { vectors ->
                vectors.resetForModel(spec.modelId)
                vectors.setInt8Scale(config.int8Scale)
            }
            EmbeddingScheduler.scheduleCharging(WorkManager.getInstance(context), after = false)
        }
    }
}
