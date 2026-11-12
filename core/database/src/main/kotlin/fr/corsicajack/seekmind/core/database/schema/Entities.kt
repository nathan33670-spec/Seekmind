package fr.corsicajack.seekmind.core.database.schema

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// Tables of docs/specs/stockage.md §3.1, column for column (snake_case names, dates in ms since the
// epoch, UTC). SchemaConformanceTest compares the created schema with the normative SQL of the spec.

@Entity(
    tableName = "capture",
    indices = [
        Index(name = "idx_capture_last_seen", value = ["last_seen_at"]),
        Index(name = "idx_capture_pkg_seen", value = ["package_name", "last_seen_at"]),
    ],
)
data class CaptureEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "first_seen_at") val firstSeenAt: Long,
    @ColumnInfo(name = "last_seen_at") val lastSeenAt: Long,
    @ColumnInfo(name = "seen_count", defaultValue = "1") val seenCount: Int = 1,
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "activity_name") val activityName: String?,
    @ColumnInfo(name = "window_title") val windowTitle: String?,
    val url: String?,
    /** Normalised and redacted text, lines separated by '\n'. */
    val text: String,
    /** Bits: 1 = tree, 2 = OCR. */
    @ColumnInfo(name = "text_source") val textSource: Int,
    /** 64-bit fingerprint (signed integer). */
    val simhash: Long,
    @ColumnInfo(name = "char_count") val charCount: Int,
)

@Entity(
    tableName = "chunk",
    foreignKeys = [
        ForeignKey(
            entity = CaptureEntity::class,
            parentColumns = ["id"],
            childColumns = ["capture_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(name = "idx_chunk_capture", value = ["capture_id"]),
        Index(name = "idx_chunk_state", value = ["state", "id"]),
    ],
)
data class ChunkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "capture_id") val captureId: Long,
    val ordinal: Int,
    /** Position in capture.text. */
    @ColumnInfo(name = "start_offset") val startOffset: Int,
    @ColumnInfo(name = "end_offset") val endOffset: Int,
    /** 0 pending, 1 embedded, 2 failed. */
    @ColumnInfo(defaultValue = "0") val state: Int = 0,
    @ColumnInfo(defaultValue = "0") val attempts: Int = 0,
)

@Entity(
    tableName = "embedding",
    foreignKeys = [
        ForeignKey(
            entity = ChunkEntity::class,
            parentColumns = ["id"],
            childColumns = ["chunk_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        // The UNIQUE constraint of the spec; Room expresses it as a unique index.
        Index(name = "idx_embedding_chunk", value = ["chunk_id"], unique = true),
        Index(name = "idx_embedding_capture", value = ["capture_id"]),
    ],
)
class EmbeddingEntity(
    /** Insertion order: drives the incremental loading of the vector index. */
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "chunk_id") val chunkId: Long,
    @ColumnInfo(name = "capture_id") val captureId: Long,
    @ColumnInfo(name = "model_id") val modelId: Int,
    /** 384 bytes. */
    @ColumnInfo(name = "vec_i8") val vecI8: ByteArray,
    /** 48 bytes. */
    @ColumnInfo(name = "vec_bin") val vecBin: ByteArray,
)

@Entity(tableName = "app_rule")
data class AppRuleEntity(
    @PrimaryKey @ColumnInfo(name = "package_name") val packageName: String,
    /** 1 excluded, 2 included. */
    val mode: Int,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(tableName = "secure_probe", primaryKeys = ["package_name", "activity_name"])
data class SecureProbeEntity(
    @ColumnInfo(name = "package_name") val packageName: String,
    /** '' when unknown. */
    @ColumnInfo(name = "activity_name") val activityName: String,
    @ColumnInfo(name = "is_secure") val isSecure: Boolean,
    @ColumnInfo(name = "probed_at") val probedAt: Long,
)

@Entity(tableName = "credit_ledger")
data class CreditLedgerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    val delta: Int,
    /** WELCOME, REWARD, COURTESY, DAILY, SEARCH. */
    val reason: String,
    /** Query key, when relevant. */
    val ref: String?,
)

/** A single row (id = 1). The spec's CHECK (id = 1) cannot be declared to Room: the store writes id 1 only. */
@Entity(tableName = "license")
data class LicenseEntity(
    @PrimaryKey val id: Int = SINGLE_ROW_ID,
    @ColumnInfo(name = "license_id") val licenseId: String,
    val plan: String,
    val token: String,
    @ColumnInfo(name = "activated_at") val activatedAt: Long,
) {
    companion object {
        const val SINGLE_ROW_ID = 1
    }
}

@Entity(tableName = "search_history", indices = [Index(name = "idx_search_history_at", value = ["at"])])
data class SearchHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    val query: String,
    /** Normalised query and filters. */
    @ColumnInfo(name = "query_key") val queryKey: String,
    @ColumnInfo(name = "result_count") val resultCount: Int,
    val charged: Boolean,
)

@Entity(tableName = "metric_daily", primaryKeys = ["day", "key"])
data class MetricDailyEntity(
    /** YYYY-MM-DD, local time. */
    val day: String,
    val key: String,
    val value: Long,
)

@Entity(tableName = "meta")
data class MetaEntity(@PrimaryKey val key: String, val value: String)
