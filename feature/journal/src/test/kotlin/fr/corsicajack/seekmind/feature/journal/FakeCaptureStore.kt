package fr.corsicajack.seekmind.feature.journal

import fr.corsicajack.seekmind.core.database.store.CaptureStore
import fr.corsicajack.seekmind.core.database.store.HashRef
import fr.corsicajack.seekmind.core.database.store.JournalCursor
import fr.corsicajack.seekmind.core.database.store.NewCapture
import fr.corsicajack.seekmind.core.database.store.NewChunk
import fr.corsicajack.seekmind.core.database.store.StorageStats
import fr.corsicajack.seekmind.core.model.Capture
import fr.corsicajack.seekmind.core.model.CaptureSummary
import java.util.concurrent.CopyOnWriteArrayList

/** The journal part of [CaptureStore], in memory, with the ordering of the real query. */
class FakeCaptureStore(rows: List<CaptureSummary>) : CaptureStore {
    val rows = CopyOnWriteArrayList(rows)
    val pageRequests = CopyOnWriteArrayList<Pair<JournalCursor?, String?>>()

    override suspend fun page(before: JournalCursor?, packageName: String?, limit: Int): List<CaptureSummary> {
        pageRequests += before to packageName
        return rows
            .sortedWith(compareByDescending<CaptureSummary> { it.lastSeenAt }.thenByDescending { it.id })
            .filter { packageName == null || it.packageName == packageName }
            .filter {
                before == null || it.lastSeenAt < before.lastSeenAt ||
                    (it.lastSeenAt == before.lastSeenAt && it.id < before.captureId)
            }
            .take(limit)
    }

    override suspend fun packages(): List<String> =
        rows.sortedByDescending { it.lastSeenAt }.map { it.packageName }.distinct()

    override suspend fun delete(captureId: Long) {
        rows.removeAll { it.id == captureId }
    }

    override suspend fun deleteByRange(from: Long, to: Long) {
        rows.removeAll { it.firstSeenAt <= to && it.lastSeenAt >= from }
    }

    override suspend fun insert(capture: NewCapture, chunks: List<NewChunk>): Long = unused()

    override suspend fun insertAll(items: List<Pair<NewCapture, List<NewChunk>>>): List<Long> = unused()

    override suspend fun touch(captureId: Long, seenAt: Long): Boolean = unused()

    override suspend fun recentHashes(packageName: String, limit: Int): List<HashRef> = unused()

    override suspend fun get(captureId: Long): Capture? = unused()

    override suspend fun deleteByPackage(packageName: String) = unused()

    override suspend fun stats(): StorageStats = unused()

    private fun unused(): Nothing = error("not used by the journal")

    companion object {
        fun summary(
            id: Long,
            at: Long,
            pkg: String = "com.example.notes",
            title: String? = null,
            preview: String = "texte $id",
        ) = CaptureSummary(id, at, at, 1, pkg, title, null, preview, 1)
    }
}
