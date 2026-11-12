package fr.corsicajack.seekmind.engine.capture.stability

import fr.corsicajack.seekmind.core.common.Clock
import fr.corsicajack.seekmind.core.model.config.CaptureConfig

/**
 * Waits for the screen to be stable before a capture (docs/specs/capture.md §3). Pure Kotlin, driven
 * by an injected clock and timer, called on the capture thread only (not thread-safe).
 *
 * - Every event re-arms the quiet delay ([CaptureConfig.quietMs]).
 * - A continuous flow of events still fires after the maximum wait, counted from the first event.
 * - Two firings are never closer than [CaptureConfig.minIntervalMs].
 * - A firing due while a capture is in progress is remembered, once, and replayed at its end.
 * - Adaptive slowdown (§3.2): once [CaptureConfig.dedupStreakForBackoff] consecutive captures of the
 *   same window were deduplicated, every further deduplication doubles the maximum wait, up to
 *   [CaptureConfig.maxWaitCeilingMs]; a stored capture or a new window brings it back.
 */
class Debouncer(
    private val config: CaptureConfig,
    private val clock: Clock,
    private val timer: Timer,
    private val onStable: () -> Unit,
) {
    private var firstEventAt: Long? = null
    private var lastFireAt: Long = Long.MIN_VALUE / 2
    private var capturing = false
    private var pending = false
    private var maxWaitMs = config.maxWaitMs
    private var dedupStreak = 0
    private var lastWindow: Any? = null

    /** The maximum wait in force, for the diagnostics. */
    val currentMaxWaitMs: Long get() = maxWaitMs

    /** An accessibility event that may change the screen. */
    fun onEvent() {
        val now = clock.elapsedRealtime()
        val first = firstEventAt ?: now.also { firstEventAt = it }
        val deadline = minOf(now + config.quietMs, first + maxWaitMs)
        timer.schedule(maxOf(deadline, lastFireAt + config.minIntervalMs), ::fire)
    }

    /** The foreground window changed: the slowdown of the previous one no longer applies. */
    fun onWindowChanged(window: Any) {
        if (window != lastWindow) {
            lastWindow = window
            resetSlowdown()
        }
    }

    /**
     * End of the capture started by [onStable]. [deduplicated]: nothing new was found on [window].
     * Replays the firing remembered meanwhile, if any.
     */
    fun onCaptureFinished(window: Any, deduplicated: Boolean) {
        capturing = false
        when {
            window != lastWindow -> {
                lastWindow = window
                resetSlowdown()
                if (deduplicated) dedupStreak = 1
            }

            !deduplicated -> resetSlowdown()

            else -> {
                dedupStreak++
                if (dedupStreak >= config.dedupStreakForBackoff) {
                    maxWaitMs = minOf(maxWaitMs * 2, config.maxWaitCeilingMs)
                }
            }
        }
        if (pending) {
            pending = false
            timer.schedule(maxOf(clock.elapsedRealtime(), lastFireAt + config.minIntervalMs), ::fire)
        }
    }

    /** Forgets everything pending: the service pauses or disconnects. */
    fun reset() {
        timer.cancel()
        firstEventAt = null
        pending = false
        capturing = false
    }

    private fun fire() {
        if (capturing) {
            pending = true
            return
        }
        firstEventAt = null
        lastFireAt = clock.elapsedRealtime()
        capturing = true
        onStable()
    }

    private fun resetSlowdown() {
        dedupStreak = 0
        maxWaitMs = config.maxWaitMs
    }
}
