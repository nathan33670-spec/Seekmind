package fr.corsicajack.seekmind.core.common

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** Coroutine dispatchers, injected rather than referenced directly (AGENTS.md §3). */
interface DispatcherProvider {
    /** Main thread: user interface only. */
    val main: CoroutineDispatcher

    /** Database, files, Binder calls. */
    val io: CoroutineDispatcher

    /** CPU-bound work: text processing, OCR post-processing, vector search. */
    val default: CoroutineDispatcher
}

object DefaultDispatcherProvider : DispatcherProvider {
    override val main: CoroutineDispatcher get() = Dispatchers.Main
    override val io: CoroutineDispatcher get() = Dispatchers.IO
    override val default: CoroutineDispatcher get() = Dispatchers.Default
}
