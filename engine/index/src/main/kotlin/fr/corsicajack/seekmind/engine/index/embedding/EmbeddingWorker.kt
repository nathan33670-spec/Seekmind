package fr.corsicajack.seekmind.engine.index.embedding

import android.content.Context
import android.os.BatteryManager
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import fr.corsicajack.seekmind.core.common.SmLog
import java.util.concurrent.TimeUnit

/**
 * Runs the embedding batches (docs/specs/indexation.md §7). Built by the worker factory of :app, which
 * passes a runner on the database of the process, or null while it is not open. A model that cannot be
 * loaded asks for a retry, with an exponential backoff; any other end is a success, even with fragments
 * left: the next period goes on. The charging work plans its successor only when its deadline ended it
 * with fragments left ([EmbeddingScheduler.planAgain]).
 */
class EmbeddingWorker(
    context: Context,
    params: WorkerParameters,
    private val runner: () -> EmbeddingBatchRunner?,
    private val pendingCount: suspend () -> Int,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // An unavailable database is the interface's business: nothing to embed.
        val batches = runner() ?: return Result.success()
        val battery = applicationContext.getSystemService(BatteryManager::class.java)
        val report = batches.run(isStopped = { isStopped }, charging = { battery?.isCharging == true })
        SmLog.i(
            TAG,
            "embedding_run",
            "embedded" to report.embedded,
            "failed" to report.failed,
            "batches" to report.batches,
            "reason" to report.reason.ordinal,
            "duration_ms" to report.durationMs,
            "median_ms_per_chunk" to report.medianMsPerChunk,
        )
        if (report.reason == StopReason.MODEL_UNAVAILABLE) return Result.retry()
        if (tags.contains(EmbeddingScheduler.CHARGING) && EmbeddingScheduler.planAgain(report.reason, pendingCount())) {
            EmbeddingScheduler.scheduleCharging(WorkManager.getInstance(applicationContext), after = true)
        }
        return Result.success()
    }

    private companion object {
        const val TAG = "Embedding"
    }
}

/**
 * Planning of [EmbeddingWorker] (docs/specs/indexation.md §7.1), **no network constraint**: every 15 min
 * with the battery not low, and a one-time work on charge, planned again while fragments are left.
 */
object EmbeddingScheduler {
    const val PERIODIC = "embedding-periodic"
    const val CHARGING = "embedding-charging"

    fun periodicRequest(): PeriodicWorkRequest = PeriodicWorkRequestBuilder<EmbeddingWorker>(15, TimeUnit.MINUTES)
        .setConstraints(constraints { setRequiresBatteryNotLow(true) })
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
        .addTag(PERIODIC)
        .build()

    fun chargingRequest(): OneTimeWorkRequest = OneTimeWorkRequestBuilder<EmbeddingWorker>()
        .setConstraints(constraints { setRequiresCharging(true) })
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
        .addTag(CHARGING)
        .build()

    /**
     * Whether the charging work plans its successor: only when its deadline stopped it with fragments
     * left. Not when the governor suspends the embeddings (a successor would start at once, again and
     * again, while the phone is hot on charge: the periodic work resumes later), nor when WorkManager
     * stopped it (WorkManager runs it again once its constraints hold), nor when the model is missing
     * (WorkManager retries with a backoff).
     */
    fun planAgain(reason: StopReason, pending: Int): Boolean = reason == StopReason.DEADLINE && pending > 0

    /** At start-up and when the service connects; keeps the works already planned. */
    fun schedule(workManager: WorkManager) {
        workManager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, periodicRequest())
        scheduleCharging(workManager, after = false)
    }

    /** [after]: from the running charging work itself, which [ExistingWorkPolicy.KEEP] would ignore. */
    fun scheduleCharging(workManager: WorkManager, after: Boolean) {
        val policy = if (after) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP
        workManager.enqueueUniqueWork(CHARGING, policy, chargingRequest())
    }

    private fun constraints(block: Constraints.Builder.() -> Unit): Constraints = Constraints.Builder()
        // Explicit: a network constraint would need ACCESS_NETWORK_STATE, absent from `offline`.
        .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
        .apply(block)
        .build()
}
