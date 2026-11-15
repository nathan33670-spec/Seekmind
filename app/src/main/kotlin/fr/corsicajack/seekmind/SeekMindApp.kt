package fr.corsicajack.seekmind

import android.app.Application
import androidx.work.Configuration
import androidx.work.WorkManager
import dagger.hilt.android.HiltAndroidApp
import fr.corsicajack.seekmind.core.database.maintenance.MaintenanceScheduler
import fr.corsicajack.seekmind.engine.index.embedding.EmbeddingScheduler
import fr.corsicajack.seekmind.work.SeekMindWorkerFactory
import javax.inject.Inject

/**
 * Application entry point and root of the Hilt graph. WorkManager is initialised on demand with the
 * worker factory of the graph (its default initializer is removed from the manifest).
 */
@HiltAndroidApp
class SeekMindApp :
    Application(),
    Configuration.Provider {
    @Inject
    lateinit var workerFactory: SeekMindWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        // Daily maintenance of the database (docs/specs/stockage.md §6).
        val workManager = WorkManager.getInstance(this)
        MaintenanceScheduler.schedule(workManager)
        // Embedding batches (docs/specs/indexation.md §7.1); planned again when the service connects (M3-05).
        EmbeddingScheduler.schedule(workManager)
    }
}
