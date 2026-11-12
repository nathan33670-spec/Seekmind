package fr.corsicajack.seekmind.core.database.store

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.sqlite.db.SupportSQLiteQuery
import fr.corsicajack.seekmind.core.database.schema.CaptureEntity
import fr.corsicajack.seekmind.core.database.schema.ChunkEntity
import fr.corsicajack.seekmind.core.database.schema.EmbeddingEntity

internal const val INDEX_GENERATION = "index_generation"
internal const val MODEL_ID = "model_id"

/** Row types read by the DAOs. */
internal data class SummaryRow(
    val id: Long,
    val first_seen_at: Long,
    val last_seen_at: Long,
    val seen_count: Int,
    val package_name: String,
    val window_title: String?,
    val url: String?,
    val preview: String,
    val text_source: Int,
)

internal data class HashRow(val id: Long, val simhash: Long, val first_seen_at: Long, val last_seen_at: Long)

internal data class StatsRow(val count: Int, val oldest: Long?)

internal data class LexicalResult(val id: Long, val rank: Double, val snip: String)

internal data class PendingRow(
    val id: Long,
    val capture_id: Long,
    val start_offset: Int,
    val end_offset: Int,
    val attempts: Int,
    val window_title: String?,
    val text: String,
)

internal class BinaryResult(
    val id: Long,
    val capture_id: Long,
    val first_seen_at: Long,
    val last_seen_at: Long,
    val package_name: String,
    val vec_bin: ByteArray,
)

internal class Int8Result(val id: Long, val capture_id: Long, val vec_i8: ByteArray)

@Dao
internal abstract class CaptureDao {
    @Insert
    abstract suspend fun insertCapture(capture: CaptureEntity): Long

    @Insert
    abstract suspend fun insertChunks(chunks: List<ChunkEntity>)

    @Transaction
    open suspend fun insert(capture: CaptureEntity, chunks: (Long) -> List<ChunkEntity>): Long {
        val id = insertCapture(capture)
        insertChunks(chunks(id))
        return id
    }

    @Query("UPDATE capture SET last_seen_at = :seenAt, seen_count = seen_count + 1 WHERE id = :id")
    abstract suspend fun touch(id: Long, seenAt: Long)

    @Query(
        "SELECT id, simhash, first_seen_at, last_seen_at FROM capture WHERE package_name = :pkg ORDER BY last_seen_at DESC LIMIT :limit",
    )
    abstract suspend fun recentHashes(pkg: String, limit: Int): List<HashRow>

    @Query("SELECT * FROM capture WHERE id = :id")
    abstract suspend fun get(id: Long): CaptureEntity?

    @Query("SELECT * FROM capture WHERE id IN (:ids)")
    abstract suspend fun getAll(ids: List<Long>): List<CaptureEntity>

    @Query(
        """
        SELECT id, first_seen_at, last_seen_at, seen_count, package_name, window_title, url,
               substr(text, 1, :previewChars) AS preview, text_source
        FROM capture
        WHERE (:before IS NULL OR last_seen_at < :before) AND (:pkg IS NULL OR package_name = :pkg)
        ORDER BY last_seen_at DESC
        LIMIT :limit
        """,
    )
    abstract suspend fun page(before: Long?, pkg: String?, limit: Int, previewChars: Int): List<SummaryRow>

    @Query("DELETE FROM capture WHERE id = :id")
    abstract suspend fun deleteOne(id: Long): Int

    @Query("DELETE FROM capture WHERE package_name = :pkg")
    abstract suspend fun deleteByPackageRows(pkg: String): Int

    @Query("DELETE FROM capture WHERE first_seen_at <= :to AND last_seen_at >= :from")
    abstract suspend fun deleteByRangeRows(from: Long, to: Long): Int

    @Query(
        "INSERT INTO meta(key, value) VALUES ('$INDEX_GENERATION', '1') " +
            "ON CONFLICT(key) DO UPDATE SET value = CAST(value AS INTEGER) + 1",
    )
    abstract suspend fun bumpGeneration()

    /** A deletion and the generation increment, in one transaction. */
    @Transaction
    open suspend fun deleting(delete: suspend () -> Int) {
        if (delete() > 0) bumpGeneration()
    }

    @Query("SELECT count(*) AS count, min(first_seen_at) AS oldest FROM capture")
    abstract suspend fun stats(): StatsRow

    @RawQuery
    abstract suspend fun pragmaLong(query: SupportSQLiteQuery): Long

    @RawQuery
    abstract suspend fun lexical(query: SupportSQLiteQuery): List<LexicalResult>
}

@Dao
internal abstract class VectorDao {
    @Query(
        """
        SELECT k.id, k.capture_id, k.start_offset, k.end_offset, k.attempts, c.window_title, c.text
        FROM chunk k JOIN capture c ON c.id = k.capture_id
        WHERE k.state = 0
        ORDER BY k.id DESC
        LIMIT :limit
        """,
    )
    abstract suspend fun pending(limit: Int): List<PendingRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertEmbeddings(rows: List<EmbeddingEntity>)

    @Query("UPDATE chunk SET state = 1 WHERE id IN (:chunkIds)")
    abstract suspend fun markEmbedded(chunkIds: List<Long>)

    @Transaction
    open suspend fun save(rows: List<EmbeddingEntity>) {
        insertEmbeddings(rows)
        markEmbedded(rows.map { it.chunkId })
    }

    @Query(
        "UPDATE chunk SET attempts = attempts + 1, state = CASE WHEN attempts + 1 >= :maxAttempts THEN 2 ELSE state END WHERE id = :id",
    )
    abstract suspend fun markFailed(id: Long, maxAttempts: Int)

    @Query(
        """
        SELECT e.id, e.capture_id, c.first_seen_at, c.last_seen_at, c.package_name, e.vec_bin
        FROM embedding e JOIN capture c ON c.id = e.capture_id
        WHERE e.id > :after
        ORDER BY e.id
        LIMIT :limit
        """,
    )
    abstract suspend fun binaryAfter(after: Long, limit: Int): List<BinaryResult>

    @Query("SELECT id, capture_id, vec_i8 FROM embedding WHERE id IN (:ids)")
    abstract suspend fun int8(ids: List<Long>): List<Int8Result>

    @Query("SELECT count(*) FROM chunk WHERE state = 0")
    abstract suspend fun pendingCount(): Int

    @Query("DELETE FROM embedding")
    abstract suspend fun deleteEmbeddings()

    @Query("UPDATE chunk SET state = 0, attempts = 0")
    abstract suspend fun resetChunks()

    @Query(
        "INSERT INTO meta(key, value) VALUES ('$MODEL_ID', :modelId) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
    )
    abstract suspend fun setModelId(modelId: String)

    @Query(
        "INSERT INTO meta(key, value) VALUES ('$INDEX_GENERATION', '1') " +
            "ON CONFLICT(key) DO UPDATE SET value = CAST(value AS INTEGER) + 1",
    )
    abstract suspend fun bumpGeneration()

    @Transaction
    open suspend fun reset(modelId: Int) {
        deleteEmbeddings()
        resetChunks()
        setModelId(modelId.toString())
        bumpGeneration()
    }

    @Query("SELECT CAST(value AS INTEGER) FROM meta WHERE key = '$INDEX_GENERATION'")
    abstract suspend fun generation(): Long?
}
