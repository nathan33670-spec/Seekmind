package fr.corsicajack.seekmind

import androidx.work.WorkManager
import dagger.hilt.android.HiltAndroidApp
import fr.corsicajack.seekmind.core.database.maintenance.MaintenanceScheduler
import fr.corsicajack.seekmind.engine.index.embedding.EmbeddingScheduler

/** Application entry point and root of the Hilt graph. */
@HiltAndroidApp
class SeekMindApp : SeekMindBaseApplication() {
    override fun onCreate() {
        super.onCreate()
        // Daily maintenance of the database (docs/specs/stockage.md §6).
        val workManager = WorkManager.getInstance(this)
        MaintenanceScheduler.schedule(workManager)
        // Embedding batches (docs/specs/indexation.md §7.1); planned again when the service connects (M3-05).
        EmbeddingScheduler.schedule(workManager)
    }
}
