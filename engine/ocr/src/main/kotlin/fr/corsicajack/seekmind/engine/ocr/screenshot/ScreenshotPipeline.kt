package fr.corsicajack.seekmind.engine.ocr.screenshot

import fr.corsicajack.seekmind.core.common.IntRect
import fr.corsicajack.seekmind.core.model.Shot

/** What the system answered to a screenshot request. */
internal sealed interface FrameResult<out F> {
    data class Frame<F>(val frame: F) : FrameResult<F>

    data class Error(val code: Int) : FrameResult<Nothing>
}

/** Sends a screenshot request to the system. */
internal interface FrameRequester<F> {
    /** True when the frame covers the whole display and must be cropped to the window (API 30-33). */
    val coversDisplay: Boolean

    suspend fun request(windowId: Int): FrameResult<F>
}

/**
 * The platform-independent part of [A11yScreenshotSource] (docs/specs/ocr.md §2): rate limit, request,
 * mapping of the system's error codes to [Shot], lifecycle of the image.
 */
internal class ScreenshotPipeline<F, B>(
    private val requester: FrameRequester<F>,
    private val ops: FrameOps<F, B>,
    private val limiter: ScreenshotRateLimiter,
    private val maxSide: Int,
) {
    suspend fun <T> withScreenshot(windowId: Int, windowBounds: IntRect, block: suspend (B) -> T): Shot<T> {
        if (!limiter.tryAcquire()) return Shot.RateLimited
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
}
