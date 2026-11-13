package fr.corsicajack.seekmind.engine.embedding.engine

import fr.corsicajack.seekmind.core.model.config.EmbeddingConfig
import fr.corsicajack.seekmind.core.model.port.EmbeddingEngine
import fr.corsicajack.seekmind.core.model.port.EmbeddingEngineProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Intra-op threads for the next session: 2, or 4 while charging (the governor, docs/specs/ocr.md §6). */
fun interface EmbeddingThreads {
    fun current(): Int
}

/**
 * The process-wide engine of the active model (docs/specs/embeddings.md §4).
 *
 * - The session is loaded on the first [use], and reloaded when the active model or the thread count
 *   has changed since.
 * - The calls are serialised: one computation at a time in the process. A block must not call [use]
 *   again (it would wait for itself).
 * - The session is closed [EmbeddingConfig.idleCloseMs] after the last use, unless a [retain] handle is
 *   open (the search screen keeps it while in the foreground), and on [release] (memory pressure, model
 *   change), after the computation in progress.
 *
 * Idle closing runs in [scope], the application scope.
 */
class SharedEmbeddingEngineProvider(
    private val factory: EmbeddingEngineFactory,
    private val activeModel: ActiveModel,
    private val threads: EmbeddingThreads,
    private val config: EmbeddingConfig,
    private val scope: CoroutineScope,
) : EmbeddingEngineProvider {
    private val mutex = Mutex()

    // Guarded by mutex.
    private var loaded: Loaded? = null

    // Guarded by lock.
    private val lock = Any()
    private var holders = 0
    private var idleClose: Job? = null

    override suspend fun <T> use(block: suspend (EmbeddingEngine) -> T): T = mutex.withLock {
        cancelIdleClose()
        try {
            block(engine())
        } finally {
            scheduleIdleClose()
        }
    }

    /** Keeps the session open until the handle is closed; the handles are counted. */
    fun retain(): AutoCloseable {
        synchronized(lock) {
            holders++
            idleClose?.cancel()
            idleClose = null
        }
        var open = true
        return AutoCloseable {
            val last = synchronized(lock) {
                if (!open) return@AutoCloseable
                open = false
                holders--
                holders == 0
            }
            if (last) scheduleIdleClose()
        }
    }

    /** Closes the session after the computation in progress; the next [use] reloads it. */
    fun release() {
        cancelIdleClose()
        scope.launch { mutex.withLock { closeEngine() } }
    }

    /** Whether a session is loaded; for the tests and the diagnostics. */
    suspend fun isLoaded(): Boolean = mutex.withLock { loaded != null }

    private suspend fun engine(): EmbeddingEngine {
        val model = activeModel.current()
        val threadCount = threads.current()
        loaded?.let { if (it.model == model && it.threads == threadCount) return it.engine }
        closeEngine()
        return factory.open(model, threadCount).also { loaded = Loaded(model, threadCount, it) }
    }

    private fun closeEngine() {
        loaded?.engine?.close()
        loaded = null
    }

    private fun cancelIdleClose() = synchronized(lock) {
        idleClose?.cancel()
        idleClose = null
    }

    private fun scheduleIdleClose() = synchronized(lock) {
        idleClose?.cancel()
        idleClose = if (holders > 0) {
            null
        } else {
            scope.launch {
                delay(config.idleCloseMs)
                mutex.withLock {
                    if (synchronized(lock) { holders == 0 }) closeEngine()
                }
            }
        }
    }

    private class Loaded(val model: LoadableModel, val threads: Int, val engine: EmbeddingEngine)
}
