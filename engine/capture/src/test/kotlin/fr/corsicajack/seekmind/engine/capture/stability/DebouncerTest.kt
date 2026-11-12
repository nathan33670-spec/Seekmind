package fr.corsicajack.seekmind.engine.capture.stability

import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.model.config.CaptureConfig
import fr.corsicajack.seekmind.core.testing.FakeClock
import org.junit.Test

/** docs/specs/capture.md §3, in virtual time (ticket M2-02). */
class DebouncerTest {
    private val clock = FakeClock(now = 0, elapsed = 0)
    private val timer = FakeTimer(clock)
    private val firings = mutableListOf<Long>()
    private val config = CaptureConfig()
    private val debouncer = Debouncer(config, clock, timer) { firings += clock.elapsedRealtime() }

    @Test
    fun `fires after the quiet delay, re-armed by every event`() {
        debouncer.onEvent()
        timer.advanceTo(1_000)
        debouncer.onEvent()
        timer.advanceTo(2_499)
        assertThat(firings).isEmpty()
        timer.advanceTo(2_500)
        assertThat(firings).containsExactly(2_500L)
    }

    @Test
    fun `a continuous flow of events still fires after the maximum wait`() {
        for (t in 0L..20_000L step 500) {
            timer.advanceTo(t)
            debouncer.onEvent()
            if (firings.isNotEmpty()) finish(deduplicated = false)
        }
        assertThat(firings.first()).isEqualTo(8_000)
        assertThat(firings[1]).isEqualTo(16_000) // the next window of events starts after the firing
    }

    @Test
    fun `two firings are never closer than the minimal interval`() {
        val quick = Debouncer(config.copy(quietMs = 100), clock, timer) { firings += clock.elapsedRealtime() }
        quick.onEvent()
        timer.advanceTo(100)
        quick.onCaptureFinished(WINDOW, deduplicated = false)
        timer.advanceTo(150)
        quick.onEvent()
        timer.advanceTo(1_099)
        assertThat(firings).containsExactly(100L)
        timer.advanceTo(1_100)
        assertThat(firings).containsExactly(100L, 1_100L).inOrder()
    }

    @Test
    fun `a firing due during a capture is remembered once and replayed at its end`() {
        debouncer.onEvent()
        timer.advanceTo(1_500) // first capture starts, not finished yet
        debouncer.onEvent()
        timer.advanceTo(3_000) // due during the capture: remembered
        debouncer.onEvent()
        timer.advanceTo(4_500) // due again: still a single replay
        assertThat(firings).containsExactly(1_500L)
        timer.advanceTo(5_000)
        debouncer.onCaptureFinished(WINDOW, deduplicated = false)
        timer.advanceTo(5_000)
        assertThat(firings).containsExactly(1_500L, 5_000L).inOrder()
        debouncer.onCaptureFinished(WINDOW, deduplicated = false)
        timer.advanceTo(20_000)
        assertThat(firings).hasSize(2)
    }

    @Test
    fun `the replay respects the minimal interval`() {
        debouncer.onEvent()
        timer.advanceTo(1_500)
        debouncer.onEvent()
        timer.advanceTo(2_000) // quiet would end at 3 000: not yet due
        val quick = config.copy(quietMs = 100)
        val d = Debouncer(quick, clock, timer) { firings += clock.elapsedRealtime() }
        firings.clear()
        d.onEvent()
        timer.advanceTo(2_100) // fires, capture in progress
        d.onEvent()
        timer.advanceTo(2_200) // would fire before 3 100: deferred by the interval, then pending
        timer.advanceTo(3_100) // due during the capture: remembered
        timer.advanceTo(3_200)
        d.onCaptureFinished(WINDOW, deduplicated = false)
        timer.advanceTo(3_200)
        assertThat(firings).containsExactly(2_100L, 3_200L).inOrder()
    }

    @Test
    fun `three deduplications of a window double the maximum wait, then up to the ceiling`() {
        repeat(2) { debouncer.onCaptureFinished(WINDOW, deduplicated = true) }
        assertThat(debouncer.currentMaxWaitMs).isEqualTo(8_000)
        debouncer.onCaptureFinished(WINDOW, deduplicated = true)
        assertThat(debouncer.currentMaxWaitMs).isEqualTo(16_000)
        debouncer.onCaptureFinished(WINDOW, deduplicated = true)
        assertThat(debouncer.currentMaxWaitMs).isEqualTo(32_000)
        repeat(3) { debouncer.onCaptureFinished(WINDOW, deduplicated = true) }
        assertThat(debouncer.currentMaxWaitMs).isEqualTo(60_000)
    }

    @Test
    fun `a stored capture or a new window brings the maximum wait back`() {
        repeat(4) { debouncer.onCaptureFinished(WINDOW, deduplicated = true) }
        assertThat(debouncer.currentMaxWaitMs).isEqualTo(32_000)
        debouncer.onCaptureFinished(WINDOW, deduplicated = false)
        assertThat(debouncer.currentMaxWaitMs).isEqualTo(8_000)

        repeat(4) { debouncer.onCaptureFinished(WINDOW, deduplicated = true) }
        debouncer.onWindowChanged("another window")
        assertThat(debouncer.currentMaxWaitMs).isEqualTo(8_000)

        repeat(4) { debouncer.onCaptureFinished("another window", deduplicated = true) }
        debouncer.onCaptureFinished("a third window", deduplicated = true)
        assertThat(debouncer.currentMaxWaitMs).isEqualTo(8_000) // a new window starts a new streak
    }

    @Test
    fun `the doubled maximum wait governs a continuous flow`() {
        repeat(3) { debouncer.onCaptureFinished(WINDOW, deduplicated = true) }
        for (t in 0L..20_000L step 500) {
            timer.advanceTo(t)
            debouncer.onEvent()
        }
        assertThat(firings.first()).isEqualTo(16_000)
    }

    @Test
    fun `reset forgets the pending firing`() {
        debouncer.onEvent()
        debouncer.reset()
        timer.advanceTo(60_000)
        assertThat(firings).isEmpty()
    }

    @Test
    fun `a ten-minute video costs about ten readings`() {
        // Continuous events; every capture of the same window is a duplicate.
        for (t in 0L..600_000L step 500) {
            timer.advanceTo(t)
            debouncer.onEvent()
            if (firings.size > finished) finish(deduplicated = true)
        }
        assertThat(firings.size).isAtMost(15)
    }

    private var finished = 0

    private fun finish(deduplicated: Boolean) {
        finished++
        debouncer.onCaptureFinished(WINDOW, deduplicated)
    }

    /** Runs the pending action when the virtual time reaches it. */
    private class FakeTimer(private val clock: FakeClock) : Timer {
        private var at: Long? = null
        private var action: (() -> Unit)? = null

        override fun schedule(atElapsedMs: Long, action: () -> Unit) {
            at = atElapsedMs
            this.action = action
        }

        override fun cancel() {
            at = null
            action = null
        }

        fun advanceTo(t: Long) {
            while (true) {
                val due = at
                if (due == null || due > t) break
                clock.elapsed = maxOf(clock.elapsed, due)
                val run = action
                at = null
                action = null
                run?.invoke()
            }
            clock.elapsed = maxOf(clock.elapsed, t)
        }
    }

    private companion object {
        const val WINDOW = "window 1"
    }
}
