package fr.corsicajack.seekmind.di

import android.content.Context
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
import fr.corsicajack.seekmind.engine.embedding.engine.SharedEmbeddingEngineProvider
import java.io.File
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/**
 * The catalogue of the embedding models (docs/specs/embeddings.md §5), created once per process off the
 * main thread. Packs are imported from a `.smpack` file in every build until the adapter of Play Asset
 * Delivery comes (its exported components await a decision on INV-12, PR of M4-07).
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
    fun installer(file: FileModelPackInstaller): ModelPackInstaller = file

    /**
     * Activation (§5.3): the engine session is closed, so that the next one loads the new model, and the
     * vectors are emptied, so that the embedding batches compute them again.
     */
    @Provides
    @Singleton
    fun catalog(
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
        }
    }
}
