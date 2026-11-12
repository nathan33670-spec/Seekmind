package fr.corsicajack.seekmind.core.database.maintenance

import android.content.Context
import android.os.BatteryManager
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import fr.corsicajack.seekmind.core.common.Clock
import fr.corsicajack.seekmind.core.common.SmLog
import fr.corsicajack.seekmind.core.database.DatabaseState
import fr.corsicajack.seekmind.core.database.settings.SettingsRepository
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Daily maintenance of the database (docs/specs/stockage.md §6). Built by the worker factory of :app,
 * which passes the database of the process, opened on first use, off the main thread.
 */
class MaintenanceWorker(
    context: Context,
    params: WorkerParameters,
    private val database: () -> DatabaseState,
    private val settings: SettingsRepository,
    private val clock: Clock,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val handle = (database() as? DatabaseState.Ready)?.database
            // An unavailable database is the interface's business (reset offered): nothing to maintain.
            ?: return Result.success()
        val current = settings.current()
        val charging = applicationContext.getSystemService(BatteryManager::class.java)?.isCharging == true
        val report = StorageMaintenance(handle).run(
            now = clock.nowMillis(),
            zone = ZoneId.systemDefault(),
            retentionDays = current.retentionDays,
            budgetBytes = current.storageBudgetBytes,
            charging = charging,
        )
        SmLog.i(
            TAG,
            "maintenance_done",
            "expired" to report.expired,
            "evicted" to report.evicted,
            "metrics" to report.metricsDeleted,
            "history" to report.historyDeleted,
            "probes" to report.probesDeleted,
            "compacted" to if (report.compacted) 1 else 0,
        )
        return Result.success()
    }

    private companion object {
        const val TAG = "Maintenance"
    }
}

/** Planning of [MaintenanceWorker]: every 24 h, battery not low, **no network constraint**. */
object MaintenanceScheduler {
    const val UNIQUE_NAME = "maintenance"

    fun request(): PeriodicWorkRequest = PeriodicWorkRequestBuilder<MaintenanceWorker>(24, TimeUnit.HOURS)
        .setConstraints(
            Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                // Explicit: a network constraint would need ACCESS_NETWORK_STATE, absent from `offline`.
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                .build(),
        )
        .build()

    /** At start-up; keeps an already planned work. */
    fun schedule(workManager: WorkManager) {
        workManager.enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request())
    }
}
