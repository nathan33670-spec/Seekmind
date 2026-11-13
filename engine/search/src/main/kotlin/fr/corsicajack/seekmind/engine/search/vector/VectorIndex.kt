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
 * Vector search (docs/specs/recherche.md §6, ADR-0005): binary codes and int8 vectors in memory, Hamming
 * preselection of [SearchConfig.binaryTopK] candidates that pass the filters, int8 rescore of the
 * candidates (no database read per search), floor, then the best fragment of each capture.
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
    private val dimensions = codec.dimensions

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
        withContext(dispatcher) {
            // Rescore in memory (the int8 vectors are kept with the codes): no database read per search.
            val similarities = FloatArray(kept)
            val best = HashMap<Long, Int>(kept * 2)
            for (n in 0 until kept) {
                val i = candidates[n]
                similarities[n] = index.dotInt8(i, query) / codec.scale
                if (similarities[n] < config.semanticFloor) continue
                val captureId = index.captureId(i)
                val current = best[captureId]
                if (current == null || similarities[n] > similarities[current]) best[captureId] = n
            }
            best.values.sortedWith(
                compareByDescending<Int> { similarities[it] }.thenByDescending { index.lastSeen(candidates[it]) },
            ).take(config.semanticTopK).map { n ->
                val i = candidates[n]
                SemanticHit(index.captureId(i), index.embeddingId(i), similarities[n])
            }
        }
    }

    private suspend fun refreshLocked(): BinaryCodes {
        val current = store.indexGeneration()
        val index = codes?.takeIf { current == generation } ?: BinaryCodes(words, dimensions).also {
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
                    row.vecI8,
                )
            }
            if (page.size < config.indexPageSize) break
        }
        return index
    }
}
