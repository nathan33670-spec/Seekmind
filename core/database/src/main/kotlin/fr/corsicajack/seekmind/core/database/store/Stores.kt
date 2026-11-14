package fr.corsicajack.seekmind.core.database.store

import fr.corsicajack.seekmind.core.model.Capture
import fr.corsicajack.seekmind.core.model.CaptureSummary
import fr.corsicajack.seekmind.core.model.SearchFilters

// Repositories of docs/specs/stockage.md §4. core:database exposes neither Room nor SQL: only these
// suspending stores and the plain types below.

/** A capture to insert (docs/specs/indexation.md §6); `char_count` is the length of [text]. */
data class NewCapture(
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val packageName: String,
    val activityName: String?,
    val windowTitle: String?,
    val url: String?,
    val text: String,
    val textSource: Int,
    val simhash: Long,
)

/** A fragment of the capture text, by its positions `[startOffset, endOffset)` (docs/specs/indexation.md §5). */
data class NewChunk(val ordinal: Int, val startOffset: Int, val endOffset: Int)

/** Position in the journal: the last row of a page. Two captures seen at the same time are ordered by id. */
data class JournalCursor(val lastSeenAt: Long, val captureId: Long) {
    companion object {
        fun after(summary: CaptureSummary) = JournalCursor(summary.lastSeenAt, summary.id)
    }
}

/** Deduplication candidate (docs/specs/indexation.md §4). */
data class HashRef(val captureId: Long, val simhash: Long, val firstSeenAt: Long, val lastSeenAt: Long)

data class StorageStats(
    val captureCount: Int,
    /** Logical size of the database: page count × page size. */
    val sizeBytes: Long,
    /** first_seen_at of the oldest capture, null when there is none. */
    val oldestCaptureAt: Long?,
)

/** A row of the lexical query (docs/specs/stockage.md §5): smaller [rank] is better. */
data class LexicalRow(
    val captureId: Long,
    val rank: Double,
    /** Matched terms between char(2) and char(3). */
    val snippet: String,
)

/** A fragment waiting for its vector, with what the model needs (docs/specs/indexation.md §5, §7). */
data class PendingChunk(
    val chunkId: Long,
    val captureId: Long,
    val windowTitle: String?,
    /** The fragment text, cut out of capture.text. */
    val text: String,
    val attempts: Int,
)

data class NewEmbedding(
    val chunkId: Long,
    val captureId: Long,
    val modelId: Int,
    /** 384 bytes. */
    val vecI8: ByteArray,
    /** 48 bytes. */
    val vecBin: ByteArray,
)

/** What the in-memory vector index keeps per vector (docs/specs/recherche.md §6.1). */
class BinaryRow(
    val embeddingId: Long,
    val captureId: Long,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val packageName: String,
    /** 48 bytes. */
    val vecBin: ByteArray,
    /** 384 bytes: the index rescores in memory, without reading the database (M5-05). */
    val vecI8: ByteArray,
)

class Int8Row(val embeddingId: Long, val captureId: Long, val vecI8: ByteArray)

/** Where the fragment of a vector lies in its capture's text (docs/specs/recherche.md §7, semantic extracts). */
data class FragmentSpan(val embeddingId: Long, val captureId: Long, val startOffset: Int, val endOffset: Int)

interface CaptureStore {
    /** The capture, its full-text entry (trigger) and its pending chunks, in one transaction. */
    suspend fun insert(capture: NewCapture, chunks: List<NewChunk>): Long

    /** Several captures and their chunks in one transaction: imports and synthetic databases. */
    suspend fun insertAll(items: List<Pair<NewCapture, List<NewChunk>>>): List<Long>

    /**
     * last_seen_at and seen_count + 1; the full-text index is untouched. False when the capture no longer
     * exists (deleted since its fingerprint was cached).
     */
    suspend fun touch(captureId: Long, seenAt: Long): Boolean

    /** The most recently seen captures of [packageName]. */
    suspend fun recentHashes(packageName: String, limit: Int): List<HashRef>

    suspend fun get(captureId: Long): Capture?

    /**
     * Journal page, most recent first by (last_seen_at, id): the captures after the cursor [before],
     * built from the last row of the previous page; null for the first page.
     */
    suspend fun page(before: JournalCursor?, packageName: String?, limit: Int): List<CaptureSummary>

    /** Packages that have captures, most recently seen first: the application filter of the journal. */
    suspend fun packages(): List<String>

    suspend fun delete(captureId: Long)

    suspend fun deleteByPackage(packageName: String)

    /** Deletes every capture seen during `[from, to]`: first_seen_at ≤ to and last_seen_at ≥ from. */
    suspend fun deleteByRange(from: Long, to: Long)

    suspend fun stats(): StorageStats
}

interface SearchStore {
    /**
     * The reference query of docs/specs/stockage.md §5. [match] is an FTS5 expression built and escaped
     * by engine:search; it is bound as a parameter, never concatenated.
     */
    suspend fun lexical(match: String, filters: SearchFilters, limit: Int): List<LexicalRow>

    suspend fun captures(ids: List<Long>): List<Capture>

    /** The fragments of the vectors [embeddingIds], in no particular order; unknown ids are skipped. */
    suspend fun fragments(embeddingIds: List<Long>): List<FragmentSpan>
}

interface VectorStore {
    /** Most recent first. */
    suspend fun pendingChunks(limit: Int): List<PendingChunk>

    /** Inserts the vectors and marks their chunks as embedded, in one transaction. */
    suspend fun saveEmbeddings(rows: List<NewEmbedding>)

    /** attempts + 1; the chunk is marked failed at IndexConfig.maxAttempts (it stays searchable by keywords). */
    suspend fun markFailed(chunkId: Long)

    /** Vectors whose id is greater than [embeddingId], in insertion order. */
    suspend fun binaryCodesAfter(embeddingId: Long, limit: Int): List<BinaryRow>

    suspend fun int8Vectors(embeddingIds: List<Long>): List<Int8Row>

    suspend fun pendingCount(): Int

    /** Empties the vectors, puts every chunk back to pending and records [modelId] as meta.model_id. */
    suspend fun resetForModel(modelId: Int)

    /**
     * meta.index_generation, 0 before any deletion: incremented in the same transaction as every
     * deletion of captures or of vectors; the in-memory index reloads when it changes.
     */
    suspend fun indexGeneration(): Long
}
