package fr.corsicajack.seekmind.engine.index.embedding

import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.database.store.BinaryRow
import fr.corsicajack.seekmind.core.database.store.Int8Row
import fr.corsicajack.seekmind.core.database.store.NewEmbedding
import fr.corsicajack.seekmind.core.database.store.PendingChunk
import fr.corsicajack.seekmind.core.database.store.VectorStore
import fr.corsicajack.seekmind.core.model.config.EmbeddingConfig
import fr.corsicajack.seekmind.core.model.config.IndexConfig
import fr.corsicajack.seekmind.core.model.port.EmbeddingEngine
import fr.corsicajack.seekmind.core.model.port.EmbeddingEngineProvider
import fr.corsicajack.seekmind.core.model.port.GovernorState
import fr.corsicajack.seekmind.core.model.port.ThermalGovernor
import fr.corsicajack.seekmind.core.model.port.ThrottleReason
import fr.corsicajack.seekmind.core.testing.FakeClock
import fr.corsicajack.seekmind.core.testing.FakeEmbeddingEngine
import fr.corsicajack.seekmind.engine.embedding.codec.VectorCodec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** docs/specs/indexation.md §7.2 and §7.3. */
class EmbeddingBatchRunnerTest {
    private val clock = FakeClock()
    private val config = IndexConfig(embedBatchSize = 4)
    private val allowed = GovernorState(allowsOcr = true, allowsEmbedding = true, embeddingThreads = 2, reason = null)
    private val governor = object : ThermalGovernor {
        override val state = MutableStateFlow(allowed)
    }
    private val store = Store()
    private val engine = FlakyEngine()
    private val provider = object : EmbeddingEngineProvider {
        var loadFails = false

        /** Loads that succeed before every later one fails, as after a release and a failed reload. */
        var loadsBeforeFailing = Int.MAX_VALUE

        override suspend fun <T> use(block: suspend (EmbeddingEngine) -> T): T {
            check(!loadFails) { "model missing" }
            check(loadsBeforeFailing-- > 0) { "reload failed" }
            return block(engine)
        }
    }
    private val pauses = mutableListOf<Long>()

    private fun runner() =
        EmbeddingBatchRunner(store, provider, VectorCodec(EmbeddingConfig().int8Scale), governor, clock, config) {
            pauses += it
            clock.advance(it)
        }

    @Test
    fun theQueueEmptiesMostRecentFirstWithAPauseOffCharge() = runTest {
        store.add(10)
        val report = runner().run()
        assertThat(report.reason).isEqualTo(StopReason.QUEUE_EMPTY)
        assertThat(report.embedded).isEqualTo(10)
        assertThat(report.batches).isEqualTo(3)
        assertThat(store.pending).isEmpty()
        // The store hands the most recent first: chunk 10, 9, 8, 7 make the first batch.
        assertThat(store.saved.first().map { it.chunkId }).containsExactly(10L, 9L, 8L, 7L).inOrder()
        assertThat(pauses).containsExactly(100L, 100L, 100L)
        assertThat(store.saved.flatten().all { it.modelId == FakeEmbeddingEngine.MODEL_ID }).isTrue()
    }

    @Test
    fun noPauseOnCharge() = runTest {
        store.add(10)
        runner().run(charging = { true })
        assertThat(pauses).isEmpty()
    }

    @Test
    fun theTitlePrecedesTheFragment() = runTest {
        store.add(1, title = "Recettes")
        runner().run()
        assertThat(engine.texts.single()).isEqualTo("Recettes · texte 1")
    }

    @Test
    fun aSuspendingGovernorStopsWithoutError() = runTest {
        store.add(10)
        governor.state.value = allowed.copy(allowsOcr = false, allowsEmbedding = false, reason = ThrottleReason.THERMAL)
        val report = runner().run()
        assertThat(report.reason).isEqualTo(StopReason.THROTTLED)
        assertThat(report.embedded).isEqualTo(0)
        // The next period goes on.
        governor.state.value = allowed
        assertThat(runner().run().embedded).isEqualTo(10)
    }

    @Test
    fun theGovernorIsReadBeforeEachBatch() = runTest {
        store.add(10)
        engine.afterBatch =
            { governor.state.value = allowed.copy(allowsEmbedding = false, reason = ThrottleReason.POWER_SAVE) }
        val report = runner().run()
        assertThat(report.reason).isEqualTo(StopReason.THROTTLED)
        assertThat(report.embedded).isEqualTo(4)
    }

    @Test
    fun theDeadlineEndsTheRun() = runTest {
        store.add(10)
        engine.afterBatch = { clock.advance(config.workerBudgetMs) }
        val report = runner().run()
        assertThat(report.reason).isEqualTo(StopReason.DEADLINE)
        assertThat(report.embedded).isEqualTo(4)
    }

    @Test
    fun aStoppedWorkEndsTheRun() = runTest {
        store.add(10)
        assertThat(runner().run(isStopped = { true }).reason).isEqualTo(StopReason.STOPPED)
    }

    @Test
    fun threeFailuresMarkTheFragmentFailedAndTheBatchGoesOn() = runTest {
        store.add(4)
        engine.failing = setOf("texte 2")
        repeat(3) { runner().run() }
        assertThat(store.attempts[2L]).isEqualTo(3)
        assertThat(store.failedChunks).containsExactly(2L)
        assertThat(store.saved.flatten().map { it.chunkId }).containsExactly(4L, 3L, 1L)
    }

    @Test
    fun aModelThatCannotLoadCountsNothingAgainstTheFragments() = runTest {
        store.add(4)
        provider.loadFails = true
        val report = runner().run()
        assertThat(report.reason).isEqualTo(StopReason.MODEL_UNAVAILABLE)
        assertThat(store.attempts).isEmpty()
        assertThat(store.pending).hasSize(4)
    }

    @Test
    fun anEngineLostBetweenTwoBatchesCountsNothingAgainstTheFragments() = runTest {
        store.add(10)
        // The first use checks the model, the second embeds a batch, every later one fails.
        provider.loadsBeforeFailing = 2
        val report = runner().run()
        assertThat(report.reason).isEqualTo(StopReason.MODEL_UNAVAILABLE)
        assertThat(report.embedded).isEqualTo(4)
        assertThat(report.failed).isEqualTo(0)
        assertThat(store.attempts).isEmpty()
        assertThat(store.pending).hasSize(6)
    }

    /**
     * Another model is activated while a batch is computed: the activation resets the vectors to the new
     * model, the engine of the next batches is the new one. No vector of the old model stays.
     */
    @Test
    fun anActivationDuringABatchLeavesNoVectorOfTheOldModel() = runTest {
        store.add(10)
        engine.afterBatch = {
            if (engine.modelId == FakeEmbeddingEngine.MODEL_ID) {
                // The batch was computed with the old engine; the activation happens before it is saved.
                store.model = 1_000
                engine.modelId = 1_000
            }
        }
        val report = runner().run()
        assertThat(report.reason).isEqualTo(StopReason.MODEL_CHANGED)
        assertThat(store.saved.flatten()).isEmpty()
        assertThat(store.pending).hasSize(10)
        // The next run goes on with the new model.
        runner().run()
        assertThat(store.saved.flatten().map { it.modelId }.toSet()).containsExactly(1_000)
        assertThat(store.pending).isEmpty()
    }

    @Test
    fun anotherInt8ScaleResetsTheVectorsFirst() = runTest {
        store.scale = 400f
        store.add(2)
        runner().run()
        assertThat(store.resets).containsExactly(FakeEmbeddingEngine.MODEL_ID)
        assertThat(store.scale).isEqualTo(EmbeddingConfig().int8Scale)
    }

    @Test
    fun theChargingWorkPlansAgainOnlyAfterItsDeadline() {
        assertThat(EmbeddingScheduler.planAgain(StopReason.DEADLINE, pending = 5)).isTrue()
        assertThat(EmbeddingScheduler.planAgain(StopReason.DEADLINE, pending = 0)).isFalse()
        // A successor would start at once and stop again, as long as the phone is hot on charge.
        assertThat(EmbeddingScheduler.planAgain(StopReason.THROTTLED, pending = 5)).isFalse()
        assertThat(EmbeddingScheduler.planAgain(StopReason.STOPPED, pending = 5)).isFalse()
        assertThat(EmbeddingScheduler.planAgain(StopReason.MODEL_UNAVAILABLE, pending = 5)).isFalse()
        assertThat(EmbeddingScheduler.planAgain(StopReason.QUEUE_EMPTY, pending = 0)).isFalse()
    }

    @Test
    fun anotherModelResetsTheVectorsFirst() = runTest {
        store.model = 1
        store.add(2)
        runner().run()
        assertThat(store.resets).containsExactly(FakeEmbeddingEngine.MODEL_ID)
        assertThat(store.model).isEqualTo(FakeEmbeddingEngine.MODEL_ID)
        store.add(1, from = 3)
        runner().run()
        assertThat(store.resets).hasSize(1)
    }

    /** Fails on the batches that hold one of [failing]. */
    private class FlakyEngine : EmbeddingEngine {
        private val delegate = FakeEmbeddingEngine()
        override var modelId = delegate.modelId
        override val dimensions = delegate.dimensions
        var failing: Set<String> = emptySet()
        var afterBatch: () -> Unit = {}
        val texts = mutableListOf<String>()

        override suspend fun embedPassages(texts: List<String>): List<FloatArray> {
            check(texts.none { it in failing }) { "engine failure" }
            this.texts += texts
            return delegate.embedPassages(texts).also { afterBatch() }
        }

        override suspend fun embedQuery(text: String) = delegate.embedQuery(text)

        override fun close() = delegate.close()
    }

    /** Pending chunks, most recent (highest id) first, as the real store. */
    private inner class Store : VectorStore {
        val pending = sortedMapOf<Long, PendingChunk>(compareByDescending { it })
        val saved = mutableListOf<List<NewEmbedding>>()
        val attempts = mutableMapOf<Long, Int>()
        val failedChunks = mutableListOf<Long>()
        val resets = mutableListOf<Int>()
        var model: Int? = FakeEmbeddingEngine.MODEL_ID
        var scale: Float? = EmbeddingConfig().int8Scale

        fun add(count: Int, from: Int = 1, title: String? = null) {
            for (id in from until from + count) {
                pending[id.toLong()] =
                    PendingChunk(id.toLong(), id.toLong(), title, "texte $id", 0)
            }
        }

        override suspend fun pendingChunks(limit: Int) = pending.values.take(limit)

        /** As the real store: the rows of another model than [model] are dropped, their chunks stay pending. */
        override suspend fun saveEmbeddings(rows: List<NewEmbedding>): Int {
            val kept = rows.filter { model == null || it.modelId == model }
            if (kept.isNotEmpty()) saved += kept
            kept.forEach { pending.remove(it.chunkId) }
            return kept.size
        }

        override suspend fun markFailed(chunkId: Long) {
            val count = (attempts[chunkId] ?: 0) + 1
            attempts[chunkId] = count
            if (count >= config.maxAttempts) {
                pending.remove(chunkId)
                failedChunks += chunkId
            }
        }

        override suspend fun resetForModel(modelId: Int) {
            resets += modelId
            model = modelId
        }

        override suspend fun modelId(): Int? = model

        override suspend fun int8Scale(): Float? = scale

        override suspend fun setInt8Scale(scale: Float) {
            this.scale = scale
        }

        override suspend fun binaryCodesAfter(embeddingId: Long, limit: Int): List<BinaryRow> = emptyList()

        override suspend fun int8Vectors(embeddingIds: List<Long>): List<Int8Row> = emptyList()

        override suspend fun pendingCount(): Int = pending.size

        override suspend fun indexGeneration(): Long = 0
    }
}
