package fr.corsicajack.seekmind.engine.index

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.common.SystemClockJvm
import fr.corsicajack.seekmind.core.database.DatabaseHandle
import fr.corsicajack.seekmind.core.database.store.NewChunk
import fr.corsicajack.seekmind.core.model.SearchFilters
import fr.corsicajack.seekmind.core.model.config.EmbeddingConfig
import fr.corsicajack.seekmind.core.model.config.IndexConfig
import fr.corsicajack.seekmind.core.model.port.EmbeddingEngine
import fr.corsicajack.seekmind.core.model.port.EmbeddingEngineProvider
import fr.corsicajack.seekmind.core.model.port.GovernorState
import fr.corsicajack.seekmind.core.model.port.ThermalGovernor
import fr.corsicajack.seekmind.core.model.port.ThrottleReason
import fr.corsicajack.seekmind.core.testing.FakeEmbeddingEngine
import fr.corsicajack.seekmind.core.testing.Models
import fr.corsicajack.seekmind.core.testing.TemporaryDatabase
import fr.corsicajack.seekmind.engine.embedding.codec.VectorCodec
import fr.corsicajack.seekmind.engine.index.embedding.EmbeddingBatchRunner
import fr.corsicajack.seekmind.engine.index.embedding.EmbeddingWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [EmbeddingWorker] run by the test builder of WorkManager on the encrypted database, with
 * [FakeEmbeddingEngine] (ticket M4-05, docs/specs/indexation.md §7).
 */
@RunWith(AndroidJUnit4::class)
class EmbeddingWorkerDeviceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val database = TemporaryDatabase(context, "embedding_worker")
    private lateinit var handle: DatabaseHandle
    private val allowed = GovernorState(allowsOcr = true, allowsEmbedding = true, embeddingThreads = 2, reason = null)
    private val governor = object : ThermalGovernor {
        override val state = MutableStateFlow(allowed)
    }
    private var engine = FakeEmbeddingEngine()
    private val provider = object : EmbeddingEngineProvider {
        override suspend fun <T> use(block: suspend (EmbeddingEngine) -> T): T = block(engine)
    }

    @Before
    fun setUp() {
        handle = database.open()
    }

    @After
    fun tearDown() = database.delete()

    private suspend fun capture(text: String, at: Long) =
        handle.captures.insert(Models.newCapture(text, at = at), listOf(NewChunk(0, 0, text.length)))

    private fun worker() = TestListenableWorkerBuilder<EmbeddingWorker>(context)
        .setWorkerFactory(
            object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ) = EmbeddingWorker(
                    appContext,
                    workerParameters,
                    runner = {
                        EmbeddingBatchRunner(
                            handle.vectors,
                            provider,
                            VectorCodec(EmbeddingConfig().int8Scale),
                            governor,
                            SystemClockJvm,
                            IndexConfig(embedBatchSize = 8, interBatchPauseMs = 0),
                        )
                    },
                    pendingCount = { handle.vectors.pendingCount() },
                )
            },
        )
        .build()

    @Test
    fun theQueueEmpties() = runBlocking {
        repeat(30) { capture("Capture numéro $it du journal", at = Models.AT + it) }
        assertThat(handle.vectors.pendingCount()).isEqualTo(30)
        assertThat(worker().doWork()).isEqualTo(ListenableWorker.Result.success())
        assertThat(handle.vectors.pendingCount()).isEqualTo(0)
        assertThat(handle.vectors.binaryCodesAfter(0, 100)).hasSize(30)
        assertThat(handle.vectors.modelId()).isEqualTo(FakeEmbeddingEngine.MODEL_ID)
    }

    @Test
    fun aSuspendingGovernorStopsWithoutErrorAndTheNextRunResumes() = runBlocking {
        repeat(5) { capture("Capture numéro $it", at = Models.AT + it) }
        governor.state.value =
            allowed.copy(allowsOcr = false, allowsEmbedding = false, reason = ThrottleReason.LOW_BATTERY)
        assertThat(worker().doWork()).isEqualTo(ListenableWorker.Result.success())
        assertThat(handle.vectors.pendingCount()).isEqualTo(5)
        governor.state.value = allowed
        assertThat(worker().doWork()).isEqualTo(ListenableWorker.Result.success())
        assertThat(handle.vectors.pendingCount()).isEqualTo(0)
    }

    @Test
    fun anotherModelEmptiesTheVectorsAndTheKeywordSearchStillWorks() = runBlocking {
        val id = capture("Recette de la tarte aux pommes", at = Models.AT)
        worker().doWork()
        assertThat(handle.vectors.modelId()).isEqualTo(FakeEmbeddingEngine.MODEL_ID)

        engine = FakeEmbeddingEngine(modelId = 1_000)
        // The first thing a run does: compare the models, reset, then embed again with the new one.
        val vectors = handle.vectors
        vectors.resetForModel(1_000)
        assertThat(vectors.pendingCount()).isEqualTo(1)
        assertThat(vectors.binaryCodesAfter(0, 10)).isEmpty()
        assertThat(handle.search.lexical("\"tarte\"", SearchFilters(), 10).map { it.captureId }).containsExactly(id)

        worker().doWork()
        assertThat(vectors.modelId()).isEqualTo(1_000)
        assertThat(vectors.pendingCount()).isEqualTo(0)
    }

    @Test
    fun theWorkerResetsOnItsOwnWhenTheModelChanged() = runBlocking {
        capture("Horaires de la piscine", at = Models.AT)
        worker().doWork()
        engine = FakeEmbeddingEngine(modelId = 1_000)
        worker().doWork()
        assertThat(handle.vectors.modelId()).isEqualTo(1_000)
        assertThat(handle.vectors.pendingCount()).isEqualTo(0)
        assertThat(handle.vectors.binaryCodesAfter(0, 10)).hasSize(1)
    }

    @Test
    fun anUnloadableModelAsksForARetry() = runBlocking {
        capture("Horaires de la piscine", at = Models.AT)
        val failing = object : EmbeddingEngineProvider {
            override suspend fun <T> use(block: suspend (EmbeddingEngine) -> T): T = error("model missing")
        }
        val worker = TestListenableWorkerBuilder<EmbeddingWorker>(context)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ) = EmbeddingWorker(
                        appContext,
                        workerParameters,
                        runner = {
                            EmbeddingBatchRunner(
                                handle.vectors,
                                failing,
                                VectorCodec(600f),
                                governor,
                                SystemClockJvm,
                            )
                        },
                        pendingCount = { handle.vectors.pendingCount() },
                    )
                },
            )
            .build()
        assertThat(worker.doWork()).isEqualTo(ListenableWorker.Result.retry())
        assertThat(handle.vectors.pendingCount()).isEqualTo(1)
    }
}
