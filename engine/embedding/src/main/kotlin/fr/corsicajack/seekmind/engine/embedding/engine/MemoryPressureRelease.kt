package fr.corsicajack.seekmind.engine.embedding.engine

import android.content.ComponentCallbacks2
import android.content.res.Configuration

/**
 * Closes the session under memory pressure (docs/specs/embeddings.md §4). Registered on the application
 * context with `registerComponentCallbacks`.
 *
 * `TRIM_MEMORY_RUNNING_LOW` and `TRIM_MEMORY_RUNNING_CRITICAL` are no longer sent from API 34: the
 * background levels, then `onLowMemory`, are the signals left there.
 */
class MemoryPressureRelease(private val provider: SharedEmbeddingEngineProvider) : ComponentCallbacks2 {
    @Suppress("DEPRECATION") // The running levels still arrive below API 34.
    override fun onTrimMemory(level: Int) {
        val running = level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
        if (running || level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) provider.release()
    }

    @Deprecated("Deprecated in Java")
    override fun onLowMemory() = provider.release()

    override fun onConfigurationChanged(newConfig: Configuration) = Unit
}
