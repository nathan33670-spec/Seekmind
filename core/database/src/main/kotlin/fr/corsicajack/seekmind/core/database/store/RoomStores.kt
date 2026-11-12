package fr.corsicajack.seekmind.core.database.store

import androidx.sqlite.db.SimpleSQLiteQuery
import fr.corsicajack.seekmind.core.database.schema.CaptureEntity
import fr.corsicajack.seekmind.core.database.schema.ChunkEntity
import fr.corsicajack.seekmind.core.database.schema.EmbeddingEntity
import fr.corsicajack.seekmind.core.model.Capture
import fr.corsicajack.seekmind.core.model.CaptureSummary
import fr.corsicajack.seekmind.core.model.SearchFilters
import fr.corsicajack.seekmind.core.model.config.IndexConfig

internal class RoomCaptureStore(private val dao: CaptureDao) : CaptureStore {
    override suspend fun insert(capture: NewCapture, chunks: List<NewChunk>): Long =
        dao.insert(capture.toEntity(), chunkRows(chunks))

    override suspend fun insertAll(items: List<Pair<NewCapture, List<NewChunk>>>): List<Long> = if (items.isEmpty()) {
        emptyList()
    } else {
        dao.insertAll(
            items.map { (capture, chunks) ->
                capture.toEntity() to
                    chunkRows(chunks)
            },
        )
    }

    private fun chunkRows(chunks: List<NewChunk>): (Long) -> List<ChunkEntity> = { id ->
        chunks.map {
            ChunkEntity(captureId = id, ordinal = it.ordinal, startOffset = it.startOffset, endOffset = it.endOffset)
        }
    }

    private fun NewCapture.toEntity() = CaptureEntity(
        firstSeenAt = firstSeenAt,
        lastSeenAt = lastSeenAt,
        packageName = packageName,
        activityName = activityName,
        windowTitle = windowTitle,
        url = url,
        text = text,
        textSource = textSource,
        simhash = simhash,
        charCount = text.length,
    )

    override suspend fun touch(captureId: Long, seenAt: Long) = dao.touch(captureId, seenAt)

    override suspend fun recentHashes(packageName: String, limit: Int): List<HashRef> =
        dao.recentHashes(packageName, limit).map { HashRef(it.id, it.simhash, it.first_seen_at, it.last_seen_at) }

    override suspend fun get(captureId: Long): Capture? = dao.get(captureId)?.toModel()

    override suspend fun page(before: Long?, packageName: String?, limit: Int): List<CaptureSummary> =
        dao.page(before, packageName, limit, PREVIEW_CHARS).map {
            CaptureSummary(
                id = it.id,
                firstSeenAt = it.first_seen_at,
                lastSeenAt = it.last_seen_at,
                seenCount = it.seen_count,
                packageName = it.package_name,
                windowTitle = it.window_title,
                url = it.url,
                preview = it.preview,
                textSource = it.text_source,
            )
        }

    override suspend fun delete(captureId: Long) = dao.deleting { dao.deleteOne(captureId) }

    override suspend fun deleteByPackage(packageName: String) = dao.deleting { dao.deleteByPackageRows(packageName) }

    override suspend fun deleteByRange(from: Long, to: Long) = dao.deleting { dao.deleteByRangeRows(from, to) }

    override suspend fun stats(): StorageStats {
        val row = dao.stats()
        val pages = dao.pragmaLong(SimpleSQLiteQuery("PRAGMA page_count"))
        val pageSize = dao.pragmaLong(SimpleSQLiteQuery("PRAGMA page_size"))
        return StorageStats(captureCount = row.count, sizeBytes = pages * pageSize, oldestCaptureAt = row.oldest)
    }

    private companion object {
        /** Characters of text shown by a journal line. */
        const val PREVIEW_CHARS = 160
    }
}

internal class RoomSearchStore(private val dao: CaptureDao) : SearchStore {
    override suspend fun lexical(match: String, filters: SearchFilters, limit: Int): List<LexicalRow> {
        val packages = filters.packages.toList()
        val range = filters.range
        val args = buildList<Any?> {
            add(match)
            add(range?.from)
            add(range?.from)
            add(range?.to)
            add(range?.to)
            add(if (packages.isEmpty()) 0 else 1)
            addAll(packages)
            add(limit)
        }
        return dao.lexical(SimpleSQLiteQuery(lexicalQuery(packages.size), args.toTypedArray()))
            .map { LexicalRow(it.id, it.rank, it.snip) }
    }

    override suspend fun captures(ids: List<Long>): List<Capture> {
        if (ids.isEmpty()) return emptyList()
        val byId = ids.chunked(MAX_BOUND_IDS).flatMap { dao.getAll(it) }.associateBy { it.id }
        return ids.mapNotNull { byId[it]?.toModel() }
    }

    internal companion object {
        /** Stays under SQLite's limit of bound parameters. */
        const val MAX_BOUND_IDS = 500

        /** The reference query of docs/specs/stockage.md §5, with one placeholder per package. */
        fun lexicalQuery(packageCount: Int): String {
            val packages = if (packageCount == 0) "NULL" else List(packageCount) { "?" }.joinToString(",")
            return """
                SELECT c.id AS id,
                       rank AS rank,
                       snippet(capture_fts, 0, char(2), char(3), '…', 24) AS snip
                FROM capture_fts
                CROSS JOIN capture c ON c.id = capture_fts.rowid
                WHERE capture_fts MATCH ?
                  AND rank MATCH 'bm25(1.0, 2.0, 0.5)'
                  AND (? IS NULL OR c.last_seen_at >= ?)
                  AND (? IS NULL OR c.first_seen_at <= ?)
                  AND (? = 0 OR c.package_name IN ($packages))
                ORDER BY rank
                LIMIT ?
            """.trimIndent()
        }
    }
}

internal class RoomVectorStore(private val dao: VectorDao, private val config: IndexConfig = IndexConfig()) :
    VectorStore {
    override suspend fun pendingChunks(limit: Int): List<PendingChunk> = dao.pending(limit).map {
        val end = it.end_offset.coerceAtMost(it.text.length)
        val start = it.start_offset.coerceIn(0, end)
        PendingChunk(it.id, it.capture_id, it.window_title, it.text.substring(start, end), it.attempts)
    }

    override suspend fun saveEmbeddings(rows: List<NewEmbedding>) {
        if (rows.isEmpty()) return
        dao.save(
            rows.map {
                EmbeddingEntity(
                    chunkId = it.chunkId,
                    captureId = it.captureId,
                    modelId = it.modelId,
                    vecI8 = it.vecI8,
                    vecBin = it.vecBin,
                )
            },
        )
    }

    override suspend fun markFailed(chunkId: Long) = dao.markFailed(chunkId, config.maxAttempts)

    override suspend fun binaryCodesAfter(embeddingId: Long, limit: Int): List<BinaryRow> =
        dao.binaryAfter(embeddingId, limit).map {
            BinaryRow(it.id, it.capture_id, it.first_seen_at, it.last_seen_at, it.package_name, it.vec_bin)
        }

    override suspend fun int8Vectors(embeddingIds: List<Long>): List<Int8Row> =
        embeddingIds.chunked(RoomSearchStore.MAX_BOUND_IDS).flatMap {
            dao.int8(it)
        }.map { Int8Row(it.id, it.capture_id, it.vec_i8) }

    override suspend fun pendingCount(): Int = dao.pendingCount()

    override suspend fun resetForModel(modelId: Int) = dao.reset(modelId)

    override suspend fun indexGeneration(): Long = dao.generation() ?: 0
}

internal fun CaptureEntity.toModel() = Capture(
    id = id,
    firstSeenAt = firstSeenAt,
    lastSeenAt = lastSeenAt,
    seenCount = seenCount,
    packageName = packageName,
    activityName = activityName,
    windowTitle = windowTitle,
    url = url,
    text = text,
    textSource = textSource,
    charCount = charCount,
)
