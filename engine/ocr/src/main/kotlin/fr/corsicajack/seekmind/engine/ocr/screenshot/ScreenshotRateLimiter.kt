package fr.corsicajack.seekmind.engine.ocr.screenshot

import fr.corsicajack.seekmind.core.common.Clock

/**
 * Keeps at least [minIntervalMs] between two screenshot requests sent to the system
 * (docs/specs/ocr.md §2). A refused request does not move the window.
 */
internal class ScreenshotRateLimiter(private val clock: Clock, private val minIntervalMs: Long) {
    private var lastRequestMs: Long? = null

    /** True, and the request counted, if the system may be asked now; false otherwise. */
    @Synchronized
    fun tryAcquire(): Boolean {
        val now = clock.elapsedRealtime()
        val last = lastRequestMs
        if (last != null && now - last < minIntervalMs) return false
        lastRequestMs = now
        return true
    }
}
