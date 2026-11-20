package fr.corsicajack.seekmind.engine.ocr.screenshot

import fr.corsicajack.seekmind.core.common.IntRect
import fr.corsicajack.seekmind.core.model.Shot
import kotlinx.coroutines.delay

/** What the system answered to a screenshot request. */
internal sealed interface FrameResult<out F> {
    data class Frame<F>(val frame: F) : FrameResult<F>

    data class Error(val code: Int) : FrameResult<Nothing>
}

/** Sends a screenshot request to the system. */
internal interface FrameRequester<F> {
    /** True when the frame covers the whole display and must be cropped to the window (API 30-33). */
    val coversDisplay: Boolean

    /** True while [windowId] is still the active window. */
    fun isActive(windowId: Int): Boolean

    suspend fun request(windowId: Int): FrameResult<F>
}

/**
 * The platform-independent part of [A11yScreenshotSource] (docs/specs/ocr.md §2): wait for the rate limit, check that the window is still active, request,
 * mapping of the system's error codes to [Shot], lifecycle of the image.
 */
internal class ScreenshotPipeline<F, B>(
    private val requester: FrameRequester<F>,
    private val ops: FrameOps<F, B>,
    private val limiter: ScreenshotRateLimiter,
    private val maxSide: Int,
    /** Sleeps for the given milliseconds: until the end of the interval between two requests. */
    private val wait: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun <T> withScreenshot(windowId: Int, windowBounds: IntRect, block: suspend (B) -> T): Shot<T> {
        if (!acquire(windowId)) return Shot.Failed(ScreenshotErrors.WINDOW_CHANGED)
        return when (val result = requester.request(windowId)) {
            is FrameResult.Error -> ScreenshotErrors.toShot(result.code)

            is FrameResult.Frame -> consumeFrame(
                frame = result.frame,
                ops = ops,
                cropTo = windowBounds.takeIf { requester.coversDisplay },
                maxSide = maxSide,
                block = block,
            )
        }
    }

    /** A request whose frame is closed at once, never copied: only its success or error counts. */
    suspend fun check(windowId: Int): Shot<Unit> {
        if (!acquire(windowId)) return Shot.Failed(ScreenshotErrors.WINDOW_CHANGED)
        return when (val result = requester.request(windowId)) {
            is FrameResult.Error -> ScreenshotErrors.toShot(result.code)

            is FrameResult.Frame -> {
                ops.close(result.frame)
                Shot.Ok(Unit)
            }
        }
    }

    /**
     * Waits for the rate limit. False when it had to wait, the frame covers the whole display, and the window
     * is no longer the active one: the display now shows another window, which the privacy gate never
     * admitted (INV-05), so no frame is requested.
     */
    private suspend fun acquire(windowId: Int): Boolean {
        val waited = limiter.acquire(wait)
        return !(waited && requester.coversDisplay && !requester.isActive(windowId))
    }
}
