package fr.corsicajack.seekmind.capture

import android.content.Context
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.corsicajack.seekmind.core.common.Clock
import fr.corsicajack.seekmind.core.database.DatabaseState
import fr.corsicajack.seekmind.core.database.store.CaptureStore
import fr.corsicajack.seekmind.core.model.IngestResult
import fr.corsicajack.seekmind.core.model.RawCapture
import fr.corsicajack.seekmind.core.model.config.IndexConfig
import fr.corsicajack.seekmind.core.model.port.CaptureSink
import fr.corsicajack.seekmind.engine.capture.routing.CaptureExecutor
import fr.corsicajack.seekmind.engine.capture.routing.CaptureThread
import fr.corsicajack.seekmind.engine.index.EmbeddingScheduler as IngestScheduler
import fr.corsicajack.seekmind.engine.index.IngestService
import fr.corsicajack.seekmind.engine.index.embedding.EmbeddingScheduler
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Storage through the database once it is open (docs/specs/indexation.md §6); a capture before that, or
 * with the database unavailable, fails and is counted as an error. Its [IngestService] keeps in memory
 * what it learnt from the stored captures (fingerprints, recurring lines): [forget] drops it.
 * Capture thread only.
 */
@Singleton
class StoredCaptureSink @Inject constructor(
    @ApplicationContext context: Context,
    private val database: Provider<DatabaseState>,
    private val clock: Clock,
    private val config: IndexConfig,
) : CaptureSink {
    private val scheduler = IngestScheduler {
        EmbeddingScheduler.scheduleCharging(WorkManager.getInstance(context), after = false)
    }
    private var service: IngestService? = null

    override suspend fun ingest(raw: RawCapture): IngestResult {
        val ingest = service ?: run {
            val ready = checkNotNull(database.get() as? DatabaseState.Ready) { "database unavailable" }
            IngestService(ready.database.captures, scheduler, clock, config).also { service = it }
        }
        return ingest.ingest(raw)
    }

    /** Forgets what was learnt from the stored captures (indexation §5.1), after a deletion. */
    fun forget() {
        service?.forget()
    }
}

/**
 * After every deletion of captures, the ingestion forgets what it learnt from them: the recurring lines of
 * a deleted application are not kept, and its history is read again from the captures left
 * (docs/specs/indexation.md §5.1). The ingestion lives on the capture thread: the forgetting is posted there.
 */
@Singleton
class CaptureForgetting internal constructor(private val thread: CaptureExecutor, private val forget: () -> Unit) {
    @Inject constructor(thread: CaptureThread, sink: StoredCaptureSink) : this(thread, sink::forget)

    fun afterDeletion() = thread.execute(forget)
}

/** [CaptureStore] whose deletions are followed by [CaptureForgetting.afterDeletion]. */
class ForgettingCaptureStore(private val delegate: CaptureStore, private val afterDeletion: () -> Unit) :
    CaptureStore by delegate {
    override suspend fun delete(captureId: Long) {
        try {
            delegate.delete(captureId)
        } finally {
            afterDeletion()
        }
    }

    override suspend fun deleteByPackage(packageName: String) {
        try {
            delegate.deleteByPackage(packageName)
        } finally {
            afterDeletion()
        }
    }

    override suspend fun deleteByRange(from: Long, to: Long) {
        try {
            delegate.deleteByRange(from, to)
        } finally {
            afterDeletion()
        }
    }
}

/** The capture store of the open database for the screens that delete; null while it is not open. */
@Singleton
class DeletableCaptures @Inject constructor(
    private val database: Provider<DatabaseState>,
    private val forgetting: CaptureForgetting,
) {
    fun store(): CaptureStore? = (database.get() as? DatabaseState.Ready)?.database?.captures
        ?.let { ForgettingCaptureStore(it, forgetting::afterDeletion) }
}
