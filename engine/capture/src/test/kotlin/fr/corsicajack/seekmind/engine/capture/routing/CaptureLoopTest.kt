package fr.corsicajack.seekmind.engine.capture.routing

import android.view.accessibility.AccessibilityEvent
import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.model.DropReason
import fr.corsicajack.seekmind.core.model.PipelineEvent
import fr.corsicajack.seekmind.core.model.config.CaptureConfig
import fr.corsicajack.seekmind.core.testing.FakeClock
import fr.corsicajack.seekmind.engine.capture.StableScreenHandler
import fr.corsicajack.seekmind.engine.capture.meta.ActivityTracker
import org.junit.Test

/** docs/specs/capture.md §2, §3 on the capture thread (ticket M2-01). */
class CaptureLoopTest {
    private val clock = FakeClock()
    private val executor = FakeCaptureExecutor()
    private val activities = ActivityTracker({ _, c -> c.endsWith("Activity") })
    private var calls = 0
    private var answer: () -> PipelineEvent = { PipelineEvent.Stored(1) }
    private val handler = StableScreenHandler {
        calls++
        answer()
    }

    @Test
    fun `a window change records the activity and a stable screen calls the handler`() {
        val loop = loop()
        loop.onEvent(STATE, APP, "$APP.MainActivity", 3)
        loop.onEvent(STATE, APP, "android.widget.FrameLayout", 3)
        assertThat(activities.activityOf(APP)).isEqualTo("$APP.MainActivity")
        executor.timer.fire()
        assertThat(calls).isEqualTo(1)
    }

    @Test
    fun `deduplicated captures of one window slow the debouncer down`() {
        val loop = loop()
        answer = { PipelineEvent.Deduplicated(1) }
        loop.onEvent(STATE, APP, "$APP.MainActivity", 3)
        val waits = (1..5).map {
            clock.advance(1_000)
            loop.onEvent(CONTENT, APP, null, 3)
            val first = clock.elapsed
            // Events every 500 ms: only the maximum wait lets the debouncer fire.
            while (executor.timer.at - first > clock.elapsed - first + 500) {
                clock.advance(500)
                loop.onEvent(CONTENT, APP, null, 3)
            }
            val wait = executor.timer.at - first
            clock.elapsed = executor.timer.at
            executor.timer.fire()
            wait
        }
        assertThat(waits.last()).isGreaterThan(waits.first())
        assertThat(calls).isEqualTo(5)
    }

    @Test
    fun `without a handler a stable screen does nothing and the loop goes on`() {
        val loop = loop(handler = null)
        loop.onEvent(CONTENT, APP, null, 3)
        executor.timer.fire()
        loop.onEvent(CONTENT, APP, null, 3)
        assertThat(executor.timer.pending).isNotNull()
    }

    @Test
    fun `a failing handler does not stop the loop`() {
        val loop = loop()
        answer = { error("boom") }
        loop.onEvent(CONTENT, APP, null, 3)
        executor.timer.fire()
        answer = { PipelineEvent.Dropped(DropReason.TOO_SHORT) }
        clock.advance(2_000)
        loop.onEvent(CONTENT, APP, null, 3)
        executor.timer.fire()
        assertThat(calls).isEqualTo(2)
    }

    @Test
    fun `reset cancels what is pending`() {
        val loop = loop()
        loop.onEvent(CONTENT, APP, null, 3)
        loop.reset()
        assertThat(executor.timer.pending).isNull()
    }

    @Test
    fun `a package change clears the activity cache on the capture thread`() {
        var lookups = 0
        val counting = ActivityTracker({ _, _ ->
            lookups++
            true
        })
        val loop = CaptureLoop(CaptureConfig(), clock, executor, counting, handler)
        loop.onEvent(STATE, APP, "$APP.MainActivity", 1)
        loop.onPackagesChanged()
        assertThat(executor.posted).isEqualTo(1)
        executor.runAll()
        loop.onEvent(STATE, APP, "$APP.MainActivity", 1)
        assertThat(lookups).isEqualTo(2)
    }

    private fun loop(handler: StableScreenHandler? = this.handler) =
        CaptureLoop(CaptureConfig(), clock, executor, activities, handler)

    private companion object {
        const val STATE = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        const val CONTENT = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        const val APP = "com.example.app"
    }
}
