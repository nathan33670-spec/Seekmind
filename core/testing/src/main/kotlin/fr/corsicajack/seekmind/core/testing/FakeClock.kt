package fr.corsicajack.seekmind.core.testing

import fr.corsicajack.seekmind.core.common.Clock

/** A [Clock] the test moves by hand (docs/06-qualite-tests-ci.md §2: no real sleep in tests). */
class FakeClock(var now: Long = 1_794_441_600_000L, var elapsed: Long = 0) : Clock {
    override fun nowMillis(): Long = now

    override fun elapsedRealtime(): Long = elapsed

    /** Moves both clocks forward. */
    fun advance(ms: Long) {
        now += ms
        elapsed += ms
    }
}
