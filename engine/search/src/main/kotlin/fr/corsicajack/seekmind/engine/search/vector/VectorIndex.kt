package fr.corsicajack.seekmind.engine.search.vector

import fr.corsicajack.seekmind.core.database.store.VectorStore
import fr.corsicajack.seekmind.core.model.SearchFilters
import fr.corsicajack.seekmind.core.model.config.SearchConfig
import fr.corsicajack.seekmind.engine.embedding.codec.VectorCodec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A capture found by meaning: its best fragment's vector and the rescored similarity. */
data class SemanticHit(val captureId: Long, val embeddingId: Long, val similarity: Float)

/**
 * Vector search (docs/specs/recherche.md §6, ADR-0005): binary codes in memory, Hamming preselection of
 * [SearchConfig.binaryTopK] candidates that pass the filters, int8 rescore of the candidates read from
 * the database, floor, then the best fragment of each capture.
 *
 * - Loaded lazily on the first [search], by pages of [SearchConfig.indexPageSize].
 * - Before every search: the vectors added since are read; a new `meta.index_generation` (a deletion)
 *   reloads everything.
 * - [release] frees the memory; the next search reloads.
 *
 * The query vector comes from `EmbeddingEngine.embedQuery`: the hybrid search (M5-06) computes it.
 */
class VectorIndex(
    private val store: VectorStore,
    private val config: SearchConfig,
    private val codec: VectorCodec,
    private val dispatcher: CoroutineDispatcher,
) {
    private val mutex = Mutex()
    private val words = codec.binarySize / Long.SIZE_BYTES

    // Guarded by mutex.
    private var codes: BinaryCodes? = null
    private var generation = 0L
    private val candidates = IntArray(config.binaryTopK)
    private val distances = IntArray(config.binaryTopK)

    /** Number of vectors in memory; 0 when not loaded. */
    suspend fun size(): Int = mutex.withLock { codes?.size ?: 0 }

    /** Whether the index holds no vector, after bringing it up to date: semantic search is unavailable. */
    suspend fun isEmpty(): Boolean = mutex.withLock { refreshLocked().size == 0 }

    /** Brings the index up to date without searching: the search screen can call it when it opens. */
    suspend fun refresh() {
        mutex.withLock { refreshLocked() }
    }

    /** Frees the arrays (the search screen left the foreground more than 60 s ago). */
    suspend fun release() {
        mutex.withLock { codes = null }
    }

    /**
     * At most [SearchConfig.semanticTopK] captures, best first; on equal similarity, the most recently
     * seen first. [query] is a unit vector of the active model.
     */
    suspend fun search(query: FloatArray, filters: SearchFilters): List<SemanticHit> = mutex.withLock {
        val index = refreshLocked()
        if (index.size == 0) return@withLock emptyList()
        val kept = withContext(dispatcher) {
            index.preselect(
                VectorCodec.words(codec.binary(query)),
                filters.range?.from,
                filters.range?.to,
                index.packageMask(filters),
                candidates,
                distances,
            )
        }
        if (kept == 0) return@withLock emptyList()
        val lastSeenOf = HashMap<Long, Long>(kept * 2)
        val ids = List(kept) { n ->
            val i = candidates[n]
            lastSeenOf[index.captureId(i)] = index.lastSeen(i)
            index.embeddingId(i)
        }
        val rows = store.int8Vectors(ids)
        withContext(dispatcher) {
            val best = HashMap<Long, SemanticHit>()
            for (row in rows) {
                val similarity = codec.rescore(query, row.vecI8)
                if (similarity < config.semanticFloor) continue
                val current = best[row.captureId]
                if (current == null || similarity > current.similarity) {
                    best[row.captureId] = SemanticHit(row.captureId, row.embeddingId, similarity)
                }
            }
            best.values.sortedWith(
                compareByDescending<SemanticHit> { it.similarity }.thenByDescending { lastSeenOf[it.captureId] ?: 0L },
            ).take(config.semanticTopK)
        }
    }

    private suspend fun refreshLocked(): BinaryCodes {
        val current = store.indexGeneration()
        val index = codes?.takeIf { current == generation } ?: BinaryCodes(words).also {
            codes = it
            generation = current
        }
        while (true) {
            val page = store.binaryCodesAfter(index.lastEmbeddingId, config.indexPageSize)
            for (row in page) {
                index.add(
                    row.embeddingId,
                    row.captureId,
                    row.firstSeenAt,
                    row.lastSeenAt,
                    row.packageName,
                    VectorCodec.words(row.vecBin),
                )
            }
            if (page.size < config.indexPageSize) break
        }
        return index
    }
}
