package fr.corsicajack.seekmind.engine.ocr.screenshot

import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.testing.FakeClock
import org.junit.Test

class ScreenshotRateLimiterTest {
    private val clock = FakeClock()
    private val limiter = ScreenshotRateLimiter(clock, minIntervalMs = 1_000)

    @Test
    fun `the first request passes`() {
        assertThat(limiter.tryAcquire()).isTrue()
    }

    @Test
    fun `a request within the interval is refused`() {
        limiter.tryAcquire()
        clock.advance(999)
        assertThat(limiter.tryAcquire()).isFalse()
    }

    @Test
    fun `a request after the interval passes`() {
        limiter.tryAcquire()
        clock.advance(1_000)
        assertThat(limiter.tryAcquire()).isTrue()
    }

    @Test
    fun `a refused request does not move the window`() {
        limiter.tryAcquire()
        clock.advance(600)
        assertThat(limiter.tryAcquire()).isFalse()
        clock.advance(400)
        assertThat(limiter.tryAcquire()).isTrue()
    }
}
