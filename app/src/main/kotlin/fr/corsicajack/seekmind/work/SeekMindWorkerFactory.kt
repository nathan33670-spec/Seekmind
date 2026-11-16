package fr.corsicajack.seekmind.work

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import fr.corsicajack.seekmind.core.common.Clock
import fr.corsicajack.seekmind.core.database.DatabaseState
import fr.corsicajack.seekmind.core.database.maintenance.MaintenanceWorker
import fr.corsicajack.seekmind.core.database.settings.SettingsRepository
import fr.corsicajack.seekmind.core.model.config.IndexConfig
import fr.corsicajack.seekmind.core.model.port.ThermalGovernor
import fr.corsicajack.seekmind.engine.embedding.codec.VectorCodec
import fr.corsicajack.seekmind.engine.embedding.engine.SharedEmbeddingEngineProvider
import fr.corsicajack.seekmind.engine.index.embedding.EmbeddingBatchRunner
import fr.corsicajack.seekmind.engine.index.embedding.EmbeddingWorker
import javax.inject.Inject
import javax.inject.Provider

/** Builds the workers of SeekMind with their dependencies from the Hilt graph. */
class SeekMindWorkerFactory @Inject constructor(
    private val database: Provider<DatabaseState>,
    private val settings: SettingsRepository,
    private val clock: Clock,
    private val embeddings: Provider<SharedEmbeddingEngineProvider>,
    private val codec: VectorCodec,
    private val governor: Provider<ThermalGovernor>,
    private val indexConfig: IndexConfig,
) : WorkerFactory() {
    private fun ready() = (database.get() as? DatabaseState.Ready)?.database

    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? = when (workerClassName) {
        MaintenanceWorker::class.java.name -> MaintenanceWorker(
            appContext,
            workerParameters,
            database::get,
            settings,
            clock,
        )

        EmbeddingWorker::class.java.name -> EmbeddingWorker(
            appContext,
            workerParameters,
            runner = {
                ready()?.let { handle ->
                    EmbeddingBatchRunner(handle.vectors, embeddings.get(), codec, governor.get(), clock, indexConfig)
                }
            },
            pendingCount = { ready()?.vectors?.pendingCount() ?: 0 },
        )

        else -> null
    }
}
