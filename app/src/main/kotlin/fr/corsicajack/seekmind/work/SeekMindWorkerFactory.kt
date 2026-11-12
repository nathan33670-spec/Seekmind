package fr.corsicajack.seekmind.work

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import fr.corsicajack.seekmind.core.common.Clock
import fr.corsicajack.seekmind.core.database.DatabaseState
import fr.corsicajack.seekmind.core.database.maintenance.MaintenanceWorker
import fr.corsicajack.seekmind.core.database.settings.SettingsRepository
import javax.inject.Inject
import javax.inject.Provider

/** Builds the workers of SeekMind with their dependencies from the Hilt graph. */
class SeekMindWorkerFactory @Inject constructor(
    private val database: Provider<DatabaseState>,
    private val settings: SettingsRepository,
    private val clock: Clock,
) : WorkerFactory() {
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

        else -> null
    }
}
