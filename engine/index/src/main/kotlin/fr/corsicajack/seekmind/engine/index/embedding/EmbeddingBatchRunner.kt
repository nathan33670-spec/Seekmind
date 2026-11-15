package fr.corsicajack.seekmind.engine.index.embedding

import fr.corsicajack.seekmind.core.common.Clock
import fr.corsicajack.seekmind.core.database.store.NewEmbedding
import fr.corsicajack.seekmind.core.database.store.PendingChunk
import fr.corsicajack.seekmind.core.database.store.VectorStore
import fr.corsicajack.seekmind.core.model.config.IndexConfig
import fr.corsicajack.seekmind.core.model.port.EmbeddingEngineProvider
import fr.corsicajack.seekmind.core.model.port.ThermalGovernor
import fr.corsicajack.seekmind.engine.embedding.codec.VectorCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Why a run of [EmbeddingBatchRunner] ended. */
enum class StopReason { QUEUE_EMPTY, DEADLINE, THROTTLED, STOPPED, MODEL_UNAVAILABLE }

/** What a run did: counters and durations only, never content (INV-07). */
data class BatchReport(
    val embedded: Int,
    val failed: Int,
    val batches: Int,
    val reason: StopReason,
    val durationMs: Long,
    /** Median time per embedded fragment, ms; 0 when none was embedded. */
    val medianMsPerChunk: Long,
)

/**
 * The embedding batches (docs/specs/indexation.md §7.2): the most recent pending fragments first, by
 * [IndexConfig.embedBatchSize], until the queue is empty, the deadline ([IndexConfig.workerBudgetMs]) is
 * reached, the governor suspends the embeddings or the work is stopped. Off charge, a pause of
 * [IndexConfig.interBatchPauseMs] separates two batches.
 *
 * - The model is loaded first: if it cannot be, nothing is counted against the fragments and the run
 *   ends with [StopReason.MODEL_UNAVAILABLE] (the worker retries later).
 * - When the model of the stored vectors is not the engine's, the vectors are reset first (§7.3).
 * - A failed batch is retried fragment by fragment: a fragment that fails gets one more attempt counted
 *   (failed at [IndexConfig.maxAttempts], still found by keywords), the others are saved.
 *
 * The engine is taken per batch, never for the whole run, so that a search can embed its query between
 * two batches.
 */
class EmbeddingBatchRunner(
    private val vectors: VectorStore,
    private val engines: EmbeddingEngineProvider,
    private val codec: VectorCodec,
    private val governor: ThermalGovernor,
    private val clock: Clock,
    private val config: IndexConfig = IndexConfig(),
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun run(isStopped: () -> Boolean = { false }, charging: () -> Boolean = { false }): BatchReport {
        val start = clock.elapsedRealtime()
        val deadline = start + config.workerBudgetMs
        var embedded = 0
        var failed = 0
        var batches = 0
        val perChunk = ArrayList<Long>()

        fun report(reason: StopReason) = BatchReport(
            embedded,
            failed,
            batches,
            reason,
            clock.elapsedRealtime() - start,
            perChunk.sorted().let { if (it.isEmpty()) 0 else it[it.size / 2] },
        )

        if (!governor.state.value.allowsEmbedding) return report(StopReason.THROTTLED)
        val modelId = try {
            engines.use { it.modelId }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return report(StopReason.MODEL_UNAVAILABLE)
        }
        if (vectors.modelId() != modelId) vectors.resetForModel(modelId)

        while (true) {
            if (isStopped()) return report(StopReason.STOPPED)
            if (clock.elapsedRealtime() >= deadline) return report(StopReason.DEADLINE)
            if (!governor.state.value.allowsEmbedding) return report(StopReason.THROTTLED)
            val batch = vectors.pendingChunks(config.embedBatchSize)
            if (batch.isEmpty()) return report(StopReason.QUEUE_EMPTY)

            val batchStart = clock.elapsedRealtime()
            val rows = embed(batch, modelId) ?: buildList {
                // The batch failed: fragment by fragment, so that one bad fragment does not hold the others.
                for (chunk in batch) {
                    val row = embed(listOf(chunk), modelId)?.single()
                    if (row != null) {
                        add(row)
                    } else {
                        vectors.markFailed(chunk.chunkId)
                        failed++
                    }
                }
            }
            if (rows.isNotEmpty()) {
                vectors.saveEmbeddings(rows)
                embedded += rows.size
                val each = (clock.elapsedRealtime() - batchStart) / rows.size
                repeat(rows.size) { perChunk += each }
            }
            batches++
            if (!charging()) pause(config.interBatchPauseMs)
        }
    }

    /** The rows of [chunks], or null when the engine fails on them. */
    private suspend fun embed(chunks: List<PendingChunk>, modelId: Int): List<NewEmbedding>? = try {
        val computed = engines.use { engine -> engine.embedPassages(chunks.map(::passage)) }
        chunks.zip(computed) { chunk, vector ->
            NewEmbedding(chunk.chunkId, chunk.captureId, modelId, codec.int8(vector), codec.binary(vector))
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        null
    }

    companion object {
        /** docs/specs/indexation.md §5: the engine adds "passage: ". */
        fun passage(chunk: PendingChunk): String =
            chunk.windowTitle?.takeIf { it.isNotBlank() }?.let { "$it · ${chunk.text}" } ?: chunk.text
    }
}
