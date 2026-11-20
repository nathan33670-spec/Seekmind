package fr.corsicajack.seekmind.engine.ocr.screenshot

import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.common.IntRect
import fr.corsicajack.seekmind.core.model.Shot
import fr.corsicajack.seekmind.core.testing.FakeClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test

class ScreenshotPipelineTest {
    private class Frame(val width: Int, val height: Int) {
        var closed = 0
    }

    private class Image(val width: Int, val height: Int) {
        var recycled = 0
    }

    private class Ops : FrameOps<Frame, Image> {
        val images = mutableListOf<Image>()
        val events = mutableListOf<String>()
        var unreadable = false
        var failFit = false

        override fun width(frame: Frame) = frame.width

        override fun height(frame: Frame) = frame.height

        override fun toSoftware(frame: Frame): Image? {
            if (unreadable) return null
            events += "copy"
            return Image(frame.width, frame.height).also(images::add)
        }

        override fun fit(source: Image, fit: ImageFit): Image {
            if (failFit) error("out of memory")
            events += "fit"
            return Image(fit.width, fit.height).also(images::add)
        }

        override fun recycle(image: Image) {
            events += "recycle"
            image.recycled++
        }

        override fun close(frame: Frame) {
            events += "close"
            frame.closed++
        }
    }

    private class Requester(override val coversDisplay: Boolean = false) : FrameRequester<Frame> {
        var calls = 0
        var next: () -> FrameResult<Frame> = { FrameResult.Frame(Frame(1_000, 1_000)) }
        val frames = mutableListOf<Frame>()
        var active = true
        var activeChecks = 0

        override fun isActive(windowId: Int): Boolean {
            activeChecks++
            return active
        }

        override suspend fun request(windowId: Int): FrameResult<Frame> {
            calls++
            return next().also { if (it is FrameResult.Frame) frames += it.frame }
        }
    }

    private val clock = FakeClock()
    private val ops = Ops()

    /** Runs during the wait, before the clock moves: the screen may change there. */
    private var duringWait: () -> Unit = {}

    private fun pipeline(requester: Requester) = ScreenshotPipeline(
        requester,
        ops,
        ScreenshotRateLimiter(clock, minIntervalMs = 1_000),
        maxSide = 1_600,
        wait = {
            duringWait()
            waited += it
            clock.advance(it)
        },
    )

    private var waited = 0L

    private val window = IntRect(0, 0, 1_000, 1_000)

    @Test
    fun `a check closes the frame at once, without copying it`() = runBlocking<Unit> {
        val requester = Requester()
        assertThat(pipeline(requester).check(7)).isEqualTo(Shot.Ok(Unit))
        assertThat(requester.frames.single().closed).isEqualTo(1)
        assertThat(ops.events).containsExactly("close")
    }

    @Test
    fun `a check reports a secure window, and a close second request waits for the interval`() = runBlocking<Unit> {
        val requester = Requester().apply { next = { FrameResult.Error(ScreenshotErrors.SECURE_WINDOW) } }
        val pipeline = pipeline(requester)
        assertThat(pipeline.check(7)).isEqualTo(Shot.SecureWindow)
        clock.advance(300)
        // Not refused: sent once the 1 000 ms since the previous request are over.
        assertThat(pipeline.check(7)).isEqualTo(Shot.SecureWindow)
        assertThat(requester.calls).isEqualTo(2)
        assertThat(waited).isEqualTo(700)
    }

    @Test
    fun `the system's own interval error is still reported`() = runBlocking<Unit> {
        val requester = Requester().apply { next = { FrameResult.Error(ScreenshotErrors.INTERVAL_TIME_SHORT) } }
        assertThat(pipeline(requester).check(7)).isEqualTo(Shot.RateLimited)
    }

    @Test
    fun `the block receives a live image, released afterwards`() = runBlocking<Unit> {
        val requester = Requester()
        var seen: Image? = null
        val shot = pipeline(requester).withScreenshot(7, window) { image ->
            assertThat(image.recycled).isEqualTo(0)
            assertThat(requester.frames.single().closed).isEqualTo(0)
            seen = image
            "text"
        }
        assertThat(shot).isEqualTo(Shot.Ok("text"))
        assertThat(seen!!.recycled).isEqualTo(1)
        assertThat(requester.frames.single().closed).isEqualTo(1)
        assertThat(ops.events).containsExactly("copy", "recycle", "close").inOrder()
    }

    @Test
    fun `everything is released when the block fails`() = runBlocking<Unit> {
        val requester = Requester().apply { next = { FrameResult.Frame(Frame(1_080, 2_400)) } }
        assertThrows(IllegalStateException::class.java) {
            runBlocking { pipeline(requester).withScreenshot(7, window) { error("boom") } }
        }
        assertThat(ops.images).hasSize(2)
        assertThat(ops.images.map { it.recycled }).containsExactly(1, 1)
        assertThat(requester.frames.single().closed).isEqualTo(1)
    }

    @Test
    fun `everything is released when the block is cancelled`() = runBlocking<Unit> {
        val requester = Requester()
        assertThrows(CancellationException::class.java) {
            runBlocking { pipeline(requester).withScreenshot(7, window) { throw CancellationException("stop") } }
        }
        assertThat(ops.images.single().recycled).isEqualTo(1)
        assertThat(requester.frames.single().closed).isEqualTo(1)
    }

    @Test
    fun `the frame is closed when the crop or the reduction fails`() = runBlocking<Unit> {
        val requester = Requester().apply { next = { FrameResult.Frame(Frame(1_080, 2_400)) } }
        ops.failFit = true
        assertThrows(IllegalStateException::class.java) {
            runBlocking { pipeline(requester).withScreenshot(7, window) { "never" } }
        }
        assertThat(ops.images.single().recycled).isEqualTo(1)
        assertThat(requester.frames.single().closed).isEqualTo(1)
    }

    @Test
    fun `an unreadable frame is closed and reported`() = runBlocking<Unit> {
        val requester = Requester()
        ops.unreadable = true
        var called = false
        val shot = pipeline(requester).withScreenshot(7, window) { called = true }
        assertThat(shot).isEqualTo(Shot.Failed(ScreenshotErrors.INVALID_IMAGE))
        assertThat(called).isFalse()
        assertThat(requester.frames.single().closed).isEqualTo(1)
    }

    @Test
    fun `a large image is reduced before the block`() = runBlocking<Unit> {
        val requester = Requester().apply { next = { FrameResult.Frame(Frame(1_080, 2_400)) } }
        val shot = pipeline(requester).withScreenshot(7, window) { it.width to it.height }
        assertThat(shot).isEqualTo(Shot.Ok(720 to 1_600))
        assertThat(ops.images.map { it.recycled }).containsExactly(1, 1)
        assertThat(ops.events).containsExactly("copy", "fit", "recycle", "recycle", "close").inOrder()
    }

    @Test
    fun `a display screenshot is cropped to the window`() = runBlocking<Unit> {
        val requester = Requester(coversDisplay = true).apply { next = { FrameResult.Frame(Frame(1_080, 2_400)) } }
        val shot = pipeline(requester).withScreenshot(7, IntRect(0, 200, 1_080, 1_200)) { it.width to it.height }
        assertThat(shot).isEqualTo(Shot.Ok(1_080 to 1_000))
    }

    @Test
    fun `a window screenshot is not cropped`() = runBlocking<Unit> {
        val requester = Requester(coversDisplay = false)
        val shot = pipeline(requester).withScreenshot(7, IntRect(0, 200, 500, 400)) { it.width to it.height }
        assertThat(shot).isEqualTo(Shot.Ok(1_000 to 1_000))
    }

    @Test
    fun `a window outside the display is reported without a copy`() = runBlocking<Unit> {
        val requester = Requester(coversDisplay = true)
        val shot = pipeline(requester).withScreenshot(7, IntRect(2_000, 0, 2_500, 500)) { "never" }
        assertThat(shot).isEqualTo(Shot.Failed(ScreenshotErrors.INVALID_IMAGE))
        assertThat(ops.images).isEmpty()
        assertThat(requester.frames.single().closed).isEqualTo(1)
    }

    @Test
    fun `a close request waits for the interval before calling the system`() = runBlocking<Unit> {
        val requester = Requester()
        val pipeline = pipeline(requester)
        assertThat(pipeline.withScreenshot(7, window) { 1 }).isEqualTo(Shot.Ok(1))
        clock.advance(500)
        assertThat(pipeline.withScreenshot(7, window) { 2 }).isEqualTo(Shot.Ok(2))
        // The second request reached the system only after the 1 000 ms of the interval.
        assertThat(waited).isEqualTo(500)
        assertThat(requester.calls).isEqualTo(2)
    }

    @Test
    fun `a failed request still counts for the interval`() = runBlocking<Unit> {
        val requester = Requester().apply { next = { FrameResult.Error(ScreenshotErrors.INTERNAL_ERROR) } }
        val pipeline = pipeline(requester)
        assertThat(pipeline.withScreenshot(7, window) { 1 }).isEqualTo(Shot.Failed(ScreenshotErrors.INTERNAL_ERROR))
        assertThat(pipeline.withScreenshot(7, window) { 1 }).isEqualTo(Shot.Failed(ScreenshotErrors.INTERNAL_ERROR))
        // The failed request opened the interval: the next one waited for all of it.
        assertThat(waited).isEqualTo(1_000)
        assertThat(requester.calls).isEqualTo(2)
    }

    @Test
    fun `system errors are mapped`() = runBlocking<Unit> {
        val requester = Requester()
        val pipeline = pipeline(requester)
        val expected = mapOf(
            ScreenshotErrors.SECURE_WINDOW to Shot.SecureWindow,
            ScreenshotErrors.INTERVAL_TIME_SHORT to Shot.RateLimited,
            ScreenshotErrors.INVALID_WINDOW to Shot.Failed(ScreenshotErrors.INVALID_WINDOW),
        )
        for ((code, shot) in expected) {
            clock.advance(1_000)
            requester.next = { FrameResult.Error(code) }
            var called = false
            assertThat(pipeline.withScreenshot(7, window) { called = true }).isEqualTo(shot)
            assertThat(called).isFalse()
        }
    }

    @Test
    fun `a display screenshot is not requested when the window changed during the wait`() = runBlocking<Unit> {
        val requester = Requester(coversDisplay = true)
        val pipeline = pipeline(requester)
        assertThat(pipeline.withScreenshot(7, window) { 1 }).isEqualTo(Shot.Ok(1))
        duringWait = { requester.active = false }
        var called = false
        val shot = pipeline.withScreenshot(7, window) { called = true }
        assertThat(shot).isEqualTo(Shot.Failed(ScreenshotErrors.WINDOW_CHANGED))
        assertThat(called).isFalse()
        // Only the first frame was requested and read.
        assertThat(requester.calls).isEqualTo(1)
        assertThat(ops.images).hasSize(1)
    }

    @Test
    fun `a display check is not requested when the window changed during the wait`() = runBlocking<Unit> {
        val requester = Requester(coversDisplay = true)
        val pipeline = pipeline(requester)
        assertThat(pipeline.check(7)).isEqualTo(Shot.Ok(Unit))
        duringWait = { requester.active = false }
        assertThat(pipeline.check(7)).isEqualTo(Shot.Failed(ScreenshotErrors.WINDOW_CHANGED))
        assertThat(requester.calls).isEqualTo(1)
    }

    @Test
    fun `a display screenshot after a wait goes on while the window is still active`() = runBlocking<Unit> {
        val requester = Requester(coversDisplay = true)
        val pipeline = pipeline(requester)
        assertThat(pipeline.withScreenshot(7, window) { 1 }).isEqualTo(Shot.Ok(1))
        assertThat(requester.activeChecks).isEqualTo(0)
        assertThat(pipeline.withScreenshot(7, window) { 2 }).isEqualTo(Shot.Ok(2))
        assertThat(requester.activeChecks).isEqualTo(1)
        assertThat(requester.calls).isEqualTo(2)
    }

    @Test
    fun `a window screenshot is not checked again after a wait`() = runBlocking<Unit> {
        val requester = Requester(coversDisplay = false)
        val pipeline = pipeline(requester)
        requester.active = false
        assertThat(pipeline.withScreenshot(7, window) { 1 }).isEqualTo(Shot.Ok(1))
        // The system captures the window itself and answers INVALID_WINDOW if it is gone.
        assertThat(pipeline.withScreenshot(7, window) { 2 }).isEqualTo(Shot.Ok(2))
        assertThat(requester.activeChecks).isEqualTo(0)
    }
}
