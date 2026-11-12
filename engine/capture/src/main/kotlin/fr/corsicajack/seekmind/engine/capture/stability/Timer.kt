package fr.corsicajack.seekmind.engine.capture.stability

/**
 * A single-shot timer on the capture thread (docs/specs/capture.md §3.1). Scheduling replaces the
 * pending action: at most one is ever pending.
 */
interface Timer {
    /** Runs [action] at [atElapsedMs] on the monotonic clock, replacing any pending action. */
    fun schedule(atElapsedMs: Long, action: () -> Unit)

    fun cancel()
}
