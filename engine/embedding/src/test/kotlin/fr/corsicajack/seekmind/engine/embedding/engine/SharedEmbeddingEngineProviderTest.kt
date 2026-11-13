package fr.corsicajack.seekmind.engine.embedding.engine

import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.model.config.EmbeddingConfig
import fr.corsicajack.seekmind.core.testing.FakeEmbeddingEngine
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SharedEmbeddingEngineProviderTest {
    private val config = EmbeddingConfig()
    private val factory = CountingFactory()
    private var model = BuiltInModels.bundledFr
    private var threadCount = 2

    private fun TestScope.provider() = SharedEmbeddingEngineProvider(
        factory = factory,
        activeModel = { model },
        threads = { threadCount },
        config = config,
        scope = backgroundScope,
    )

    @Test
    fun theModelIsLoadedOnTheFirstUseThenShared() = runTest {
        val provider = provider()
        assertThat(factory.opened).isEmpty()
        val first = provider.use { it }
        val second = provider.use { it }
        assertThat(first).isSameInstanceAs(second)
        assertThat(factory.opened).containsExactly(BuiltInModels.bundledFr to 2)
    }

    @Test
    fun theSessionClosesAfterTheIdleDelayAndTheNextCallReloadsIt() = runTest {
        val provider = provider()
        val first = provider.use { it } as FakeEmbeddingEngine
        advanceTimeBy(config.idleCloseMs - 1)
        runCurrent()
        assertThat(first.closed).isFalse()
        advanceTimeBy(2)
        runCurrent()
        assertThat(first.closed).isTrue()
        assertThat(provider.isLoaded()).isFalse()

        val second = provider.use { it }
        assertThat(second).isNotSameInstanceAs(first)
        assertThat(factory.opened).hasSize(2)
    }

    @Test
    fun eachUseRestartsTheIdleDelay() = runTest {
        val provider = provider()
        val engine = provider.use { it } as FakeEmbeddingEngine
        advanceTimeBy(config.idleCloseMs / 2)
        provider.use { }
        advanceTimeBy(config.idleCloseMs - 1)
        runCurrent()
        assertThat(engine.closed).isFalse()
        advanceTimeBy(2)
        runCurrent()
        assertThat(engine.closed).isTrue()
    }

    @Test
    fun aRetainedSessionStaysOpenUntilTheLastHandleIsClosed() = runTest {
        val provider = provider()
        val search = provider.retain()
        val other = provider.retain()
        val engine = provider.use { it } as FakeEmbeddingEngine
        advanceTimeBy(config.idleCloseMs * 5)
        runCurrent()
        assertThat(engine.closed).isFalse()

        search.close()
        search.close() // closing a handle twice counts once
        advanceTimeBy(config.idleCloseMs * 5)
        runCurrent()
        assertThat(engine.closed).isFalse()

        other.close()
        advanceTimeBy(config.idleCloseMs + 1)
        runCurrent()
        assertThat(engine.closed).isTrue()
    }

    @Test
    fun releaseClosesAfterTheComputationInProgress() = runTest {
        val provider = provider()
        val inside = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var engine: FakeEmbeddingEngine? = null
        val job = launch {
            provider.use {
                engine = it as FakeEmbeddingEngine
                inside.complete(Unit)
                finish.await()
                it.embedQuery("still open")
            }
        }
        inside.await()
        provider.release()
        runCurrent()
        assertThat(engine!!.closed).isFalse()
        finish.complete(Unit)
        job.join()
        runCurrent()
        assertThat(engine!!.closed).isTrue()
        assertThat(provider.isLoaded()).isFalse()
    }

    @Test
    fun releaseClosesARetainedSessionToo() = runTest {
        val provider = provider()
        provider.retain()
        val engine = provider.use { it } as FakeEmbeddingEngine
        provider.release()
        runCurrent()
        assertThat(engine.closed).isTrue()
        provider.use { }
        assertThat(factory.opened).hasSize(2)
    }

    @Test
    fun callsAreSerialised() = runTest {
        val provider = provider()
        var running = 0
        var most = 0
        val jobs = List(5) {
            launch {
                provider.use {
                    running++
                    most = maxOf(most, running)
                    kotlinx.coroutines.yield()
                    running--
                }
            }
        }
        jobs.forEach { it.join() }
        assertThat(most).isEqualTo(1)
        assertThat(factory.opened).hasSize(1)
    }

    @Test
    fun aNewActiveModelReplacesTheSession() = runTest {
        val provider = provider()
        val fr = provider.use { it } as FakeEmbeddingEngine
        val pack = ModelFiles(File("multi/model.onnx"), File("multi/vocab.bin"))
        model = LoadableModel(BuiltInModels.MULTI, ModelSource.Pack(pack))
        val multi = provider.use { it }
        assertThat(fr.closed).isTrue()
        assertThat(multi.modelId).isEqualTo(2)
    }

    @Test
    fun aNewThreadCountReloadsTheSession() = runTest {
        val provider = provider()
        val first = provider.use { it } as FakeEmbeddingEngine
        threadCount = 4
        provider.use { }
        assertThat(first.closed).isTrue()
        assertThat(factory.opened.map { it.second }).containsExactly(2, 4).inOrder()
    }

    @Test
    fun aFailedLoadIsRetriedByTheNextCall() = runTest {
        val provider = provider()
        factory.failures = 1
        assertThat(runCatching { provider.use { } }.exceptionOrNull()).hasMessageThat().contains("cannot load")
        assertThat(provider.isLoaded()).isFalse()
        provider.use { }
        assertThat(provider.isLoaded()).isTrue()
    }

    @Test
    fun anExceptionInTheBlockKeepsTheSessionAndItsIdleDelay() = runTest {
        val provider = provider()
        val engine = provider.use { it } as FakeEmbeddingEngine
        runCatching { provider.use { error("caller failure") } }
        assertThat(engine.closed).isFalse()
        advanceTimeBy(config.idleCloseMs + 1)
        runCurrent()
        assertThat(engine.closed).isTrue()
    }

    private class CountingFactory : EmbeddingEngineFactory {
        val opened = mutableListOf<Pair<LoadableModel, Int>>()
        var failures = 0

        override suspend fun open(model: LoadableModel, intraOpThreads: Int): FakeEmbeddingEngine {
            if (failures > 0) {
                failures--
                error("cannot load")
            }
            opened += model to intraOpThreads
            return FakeEmbeddingEngine(modelId = model.spec.modelId)
        }
    }
}
