package fr.corsicajack.seekmind.core.database.maintenance

import androidx.sqlite.db.SimpleSQLiteQuery
import fr.corsicajack.seekmind.core.database.DatabaseHandle
import fr.corsicajack.seekmind.core.database.schema.FtsSchema
import fr.corsicajack.seekmind.core.model.config.OcrConfig
import fr.corsicajack.seekmind.core.model.config.StorageConfig
import java.time.Instant
import java.time.ZoneId

/** What a maintenance pass did; counts only, logged without content. */
data class MaintenanceReport(
    val expired: Int,
    val evicted: Int,
    val metricsDeleted: Int,
    val historyDeleted: Int,
    val probesDeleted: Int,
    val compacted: Boolean,
    val ftsOptimized: Boolean,
)

/**
 * Retention and maintenance of docs/specs/stockage.md §6, in the order of the spec. The user's settings
 * give [run] the retention and the budget; [config] gives the rest.
 */
class StorageMaintenance(
    private val handle: DatabaseHandle,
    private val config: StorageConfig = StorageConfig(),
    private val probeTtlMs: Long = OcrConfig().probeCacheTtlMs,
) {
    private val dao: MaintenanceDao = handle.room.maintenanceDao()

    suspend fun run(
        now: Long,
        zone: ZoneId,
        retentionDays: Int?,
        budgetBytes: Long,
        charging: Boolean,
    ): MaintenanceReport {
        // 1. Age.
        val expired = if (retentionDays ==
            null
        ) {
            0
        } else {
            dao.deleting { dao.deleteSeenBefore(now - retentionDays * DAY_MS) }
        }

        // 2. Budget: the size in use, not the file size, which does not shrink after a deletion.
        var evicted = 0
        while (usedBytes() > budgetBytes && dao.captureCount() > 0) {
            evicted += dao.deleting { dao.deleteOldest(config.budgetEvictionBatch) }
            // Deletions leave tombstones in the full-text index: merge them before measuring again.
            optimizeFts()
        }

        // 3. Side tables.
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val metricsDeleted = handle.metrics.deleteBefore(
            today.minusDays(config.metricsRetentionDays.toLong()).toString(),
        )
        val historyDeleted = handle.searchHistory.deleteOlderThan(now - config.historyRetentionDays * DAY_MS)
        val probesDeleted = handle.secureProbes.deleteOlderThan(now - probeTtlMs)

        // 4. Compaction, while charging only.
        val compacted = charging && freeRatio() > config.vacuumFreeRatio
        if (compacted) {
            dao.pragma(SimpleSQLiteQuery("PRAGMA wal_checkpoint(TRUNCATE)"))
            handle.room.openHelper.writableDatabase.execSQL("VACUUM")
        }

        // 5. Weekly optimisation of the full-text index.
        val lastOptimized = dao.meta(LAST_FTS_OPTIMIZE)?.toLongOrNull()
        val ftsOptimized = evicted > 0 || lastOptimized == null || now - lastOptimized >= WEEK_MS
        if (ftsOptimized) {
            if (evicted == 0) optimizeFts()
            dao.setMeta(LAST_FTS_OPTIMIZE, now.toString())
        }

        return MaintenanceReport(
            expired,
            evicted,
            metricsDeleted,
            historyDeleted,
            probesDeleted,
            compacted,
            ftsOptimized,
        )
    }

    /** Bytes of the pages in use: page_count minus the free list, times the page size. */
    suspend fun usedBytes(): Long {
        val pages = dao.pragma(SimpleSQLiteQuery("PRAGMA page_count"))
        val free = dao.pragma(SimpleSQLiteQuery("PRAGMA freelist_count"))
        return (pages - free) * dao.pragma(SimpleSQLiteQuery("PRAGMA page_size"))
    }

    private suspend fun freeRatio(): Double {
        val pages = dao.pragma(SimpleSQLiteQuery("PRAGMA page_count"))
        return if (pages == 0L) 0.0 else dao.pragma(SimpleSQLiteQuery("PRAGMA freelist_count")).toDouble() / pages
    }

    private fun optimizeFts() = handle.room.openHelper.writableDatabase.execSQL(FtsSchema.OPTIMIZE)

    private companion object {
        const val DAY_MS = 86_400_000L
        const val WEEK_MS = 7 * DAY_MS
        const val LAST_FTS_OPTIMIZE = "fts_optimized_at"
    }
}
