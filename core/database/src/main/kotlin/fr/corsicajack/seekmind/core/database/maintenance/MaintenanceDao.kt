package fr.corsicajack.seekmind.core.database.maintenance

import androidx.room.Dao
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.sqlite.db.SupportSQLiteQuery

@Dao
internal abstract class MaintenanceDao {
    @Query("DELETE FROM capture WHERE last_seen_at < :cutoff")
    abstract suspend fun deleteSeenBefore(cutoff: Long): Int

    @Query("DELETE FROM capture WHERE id IN (SELECT id FROM capture ORDER BY last_seen_at, id LIMIT :count)")
    abstract suspend fun deleteOldest(count: Int): Int

    @Query(
        "INSERT INTO meta(key, value) VALUES ('index_generation', '1') " +
            "ON CONFLICT(key) DO UPDATE SET value = CAST(value AS INTEGER) + 1",
    )
    abstract suspend fun bumpGeneration()

    /** A deletion of captures and the generation increment, in one transaction (docs/specs/stockage.md §4). */
    @Transaction
    open suspend fun deleting(delete: suspend () -> Int): Int = delete().also { if (it > 0) bumpGeneration() }

    @Query("SELECT count(*) FROM capture")
    abstract suspend fun captureCount(): Int

    @Query("SELECT value FROM meta WHERE key = :key")
    abstract suspend fun meta(key: String): String?

    @Query("INSERT INTO meta(key, value) VALUES (:key, :value) ON CONFLICT(key) DO UPDATE SET value = excluded.value")
    abstract suspend fun setMeta(key: String, value: String)

    @RawQuery
    abstract suspend fun pragma(query: SupportSQLiteQuery): Long
}
