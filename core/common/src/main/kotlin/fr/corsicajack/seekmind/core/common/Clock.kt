package fr.corsicajack.seekmind.core.common

/**
 * Time source of every component (architecture §7): injected, so that tests control it.
 * [nowMillis] is wall-clock time (epoch, ms, UTC), for what is stored and shown; [elapsedRealtime] is
 * monotonic, for delays and budgets, and never goes back when the user changes the clock.
 */
interface Clock {
    fun nowMillis(): Long

    fun elapsedRealtime(): Long
}

/**
 * [Clock] of the JVM. On Android, :app binds a clock whose monotonic time also counts deep sleep
 * (`SystemClock.elapsedRealtime`); this one serves the JVM tools and tests.
 */
object SystemClockJvm : Clock {
    override fun nowMillis(): Long = System.currentTimeMillis()

    override fun elapsedRealtime(): Long = System.nanoTime() / 1_000_000
}
