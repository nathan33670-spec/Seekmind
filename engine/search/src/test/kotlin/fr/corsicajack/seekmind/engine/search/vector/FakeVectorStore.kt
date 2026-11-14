package fr.corsicajack.seekmind.engine.search.vector

import fr.corsicajack.seekmind.core.database.store.BinaryRow
import fr.corsicajack.seekmind.core.database.store.Int8Row
import fr.corsicajack.seekmind.core.database.store.NewEmbedding
import fr.corsicajack.seekmind.core.database.store.PendingChunk
import fr.corsicajack.seekmind.core.database.store.VectorStore
import fr.corsicajack.seekmind.engine.embedding.codec.VectorCodec

/** The vectors of [VectorStore] in memory, with the reads the index makes counted. */
internal class FakeVectorStore(private val codec: VectorCodec) : VectorStore {
    class Stored(
        val id: Long,
        val captureId: Long,
        val first: Long,
        val last: Long,
        val packageName: String,
        val vector: FloatArray,
    )

    val rows = mutableListOf<Stored>()
    private var nextId = 1L
    private var generation = 0L
    var pageReads = 0
    var int8Reads = 0

    fun add(
        captureId: Long,
        vector: FloatArray,
        packageName: String = "com.example.notes",
        first: Long = 0,
        last: Long = first,
    ): Long {
        val id = nextId++
        rows += Stored(id, captureId, first, last, packageName, vector)
        return id
    }

    fun deleteCapture(captureId: Long) {
        rows.removeAll { it.captureId == captureId }
        generation++
    }

    override suspend fun binaryCodesAfter(embeddingId: Long, limit: Int): List<BinaryRow> {
        pageReads++
        return rows.filter { it.id > embeddingId }.take(limit).map {
            BinaryRow(
                it.id,
                it.captureId,
                it.first,
                it.last,
                it.packageName,
                codec.binary(it.vector),
                codec.int8(it.vector),
            )
        }
    }

    override suspend fun int8Vectors(embeddingIds: List<Long>): List<Int8Row> {
        int8Reads++
        val wanted = embeddingIds.toSet()
        return rows.filter { it.id in wanted }.map { Int8Row(it.id, it.captureId, codec.int8(it.vector)) }
    }

    override suspend fun indexGeneration(): Long = generation

    override suspend fun pendingChunks(limit: Int): List<PendingChunk> = error("unused")

    override suspend fun saveEmbeddings(rows: List<NewEmbedding>) = error("unused")

    override suspend fun markFailed(chunkId: Long) = error("unused")

    override suspend fun pendingCount(): Int = error("unused")

    override suspend fun resetForModel(modelId: Int) = error("unused")
}
