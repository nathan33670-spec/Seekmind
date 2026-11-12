package fr.corsicajack.seekmind.engine.capture.routing

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.model.config.CaptureConfig
import fr.corsicajack.seekmind.core.testing.FakeClock
import fr.corsicajack.seekmind.engine.capture.meta.ActivityTracker
import org.junit.Test

/** docs/specs/capture.md §2, §5.3, §7 (ticket M2-01). */
class EventRouterTest {
    private val clock = FakeClock()
    private val executor = FakeCaptureExecutor()
    private val environment = FakeEnvironment()
    private val loop = CaptureLoop(CaptureConfig(), clock, executor, ActivityTracker({ _, _ -> true }), handler = null)
    private val router = EventRouter(executor, loop, environment, clock)

    @Test
    fun `events of ignored packages never wake the capture thread`() {
        attachReady()
        repeat(1_000) {
            router.onEvent(CONTENT, SEEKMIND, null, 1)
            router.onEvent(CONTENT, SYSTEM_UI, null, 2)
            router.onEvent(STATE, LAUNCHER, "$LAUNCHER.Home", 3)
            router.onEvent(CONTENT, KEYBOARD, null, 4)
            router.onEvent(CONTENT, null, null, 5)
        }
        assertThat(executor.posted).isEqualTo(0)
    }

    @Test
    fun `other events are handed to the capture thread and re-arm the debouncer`() {
        attachReady()
        router.onEvent(STATE, APP, "$APP.MainActivity", 7)
        router.onEvent(CONTENT, APP, null, 7)
        assertThat(executor.posted).isEqualTo(2)
        assertThat(executor.timer.pending).isNull()
        executor.runAll()
        assertThat(executor.timer.pending).isNotNull()
        assertThat(executor.timer.at).isEqualTo(clock.elapsed + CaptureConfig().quietMs)
    }

    @Test
    fun `nothing passes before the ignored packages and the settings are known`() {
        router.attach(SERVICE)
        router.onEvent(CONTENT, APP, null, 1)
        environment.listener!!.onIgnoredPackages(IGNORED)
        executor.settle()
        router.onEvent(CONTENT, APP, null, 1)
        assertThat(executor.posted).isEqualTo(0)
        environment.listener!!.onSettings(consented = true, pausedUntil = null)
        router.onEvent(CONTENT, APP, null, 1)
        assertThat(executor.posted).isEqualTo(1)
    }

    @Test
    fun `without consent nothing passes`() {
        attachReady(consented = false)
        router.onEvent(STATE, APP, "$APP.MainActivity", 1)
        assertThat(executor.posted).isEqualTo(0)
    }

    @Test
    fun `during a pause nothing passes, and the pause ends on time`() {
        attachReady(pausedUntil = clock.now + 60_000)
        router.onEvent(CONTENT, APP, null, 1)
        clock.advance(59_999)
        router.onEvent(CONTENT, APP, null, 1)
        assertThat(executor.posted).isEqualTo(0)
        clock.advance(1)
        router.onEvent(CONTENT, APP, null, 1)
        assertThat(executor.posted).isEqualTo(1)
    }

    @Test
    fun `a pause without limit holds`() {
        attachReady(pausedUntil = Long.MAX_VALUE)
        clock.advance(365L * 24 * 3_600_000)
        router.onEvent(CONTENT, APP, null, 1)
        assertThat(executor.posted).isEqualTo(0)
    }

    @Test
    fun `after detach nothing passes and the loop is reset`() {
        attachReady()
        router.onEvent(CONTENT, APP, null, 1)
        executor.runAll()
        assertThat(executor.timer.pending).isNotNull()
        router.detach()
        assertThat(environment.started).isFalse()
        executor.settle()
        assertThat(executor.timer.pending).isNull()
        router.onEvent(CONTENT, APP, null, 1)
        assertThat(executor.posted).isEqualTo(0)
    }

    @Test
    fun `a new ignored set applies at once`() {
        attachReady()
        environment.listener!!.onIgnoredPackages(IGNORED + APP)
        executor.settle()
        router.onEvent(CONTENT, APP, null, 1)
        assertThat(executor.posted).isEqualTo(0)
    }

    private fun attachReady(consented: Boolean = true, pausedUntil: Long? = null) {
        router.attach(SERVICE)
        environment.listener!!.onIgnoredPackages(IGNORED)
        environment.listener!!.onSettings(consented, pausedUntil)
        executor.settle()
    }

    private class FakeEnvironment : CaptureEnvironment {
        var listener: CaptureEnvironment.Listener? = null
        var started = false

        override fun start(service: AccessibilityService, listener: CaptureEnvironment.Listener) {
            this.listener = listener
            started = true
        }

        override fun stop() {
            started = false
        }
    }

    private companion object {
        const val STATE = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        const val CONTENT = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        const val APP = "com.example.app"
        const val SEEKMIND = "fr.corsicajack.seekmind"
        const val SYSTEM_UI = "com.android.systemui"
        const val LAUNCHER = "com.example.launcher"
        const val KEYBOARD = "com.example.keyboard"
        val IGNORED = setOf(SEEKMIND, SYSTEM_UI, LAUNCHER, KEYBOARD)

        /** Not connected to anything: the fake environment does not use it. */
        val SERVICE: AccessibilityService = object : AccessibilityService() {
            override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

            override fun onInterrupt() = Unit
        }
    }
}
