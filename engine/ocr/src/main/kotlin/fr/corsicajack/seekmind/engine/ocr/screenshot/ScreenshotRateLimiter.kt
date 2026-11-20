package fr.corsicajack.seekmind.engine.ocr.screenshot

import fr.corsicajack.seekmind.core.common.Clock

/**
 * Keeps at least [minIntervalMs] between two screenshot requests sent to the system
 * (docs/specs/ocr.md §2). A refused request does not move the window. [acquire] waits for the end of
 * the interval, at most [minIntervalMs], instead of refusing: a screen of a probed-always package must
 * not be rejected only because the previous capture was taken less than a second before.
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

    /** Milliseconds left before a request may be sent; 0 or less when it may be sent now. */
    @Synchronized
    fun remainingMs(): Long = lastRequestMs?.let { it + minIntervalMs - clock.elapsedRealtime() } ?: 0

    /**
     * Waits until a request may be sent, then counts it. [wait] sleeps for the given milliseconds.
     * True when it had to wait: the screen may have changed in the meantime.
     */
    suspend fun acquire(wait: suspend (Long) -> Unit): Boolean {
        var waited = false
        while (!tryAcquire()) {
            wait(remainingMs().coerceIn(1, minIntervalMs))
            waited = true
        }
        return waited
    }
}
