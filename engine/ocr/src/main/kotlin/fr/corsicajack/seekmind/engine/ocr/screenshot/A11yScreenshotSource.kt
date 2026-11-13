package fr.corsicajack.seekmind.engine.ocr.screenshot

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Build
import android.view.Display
import fr.corsicajack.seekmind.core.common.Clock
import fr.corsicajack.seekmind.core.common.IntRect
import fr.corsicajack.seekmind.core.model.Shot
import fr.corsicajack.seekmind.core.model.config.OcrConfig
import fr.corsicajack.seekmind.core.model.port.ScreenshotSource
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [ScreenshotSource] of the accessibility service (docs/specs/ocr.md §2): the window on API 34+, the
 * display cropped to the window on API 30-33. The image lives in memory only (INV-03): the hardware
 * buffer is closed and every bitmap recycled in a `finally`, and the bitmap never leaves the block.
 * One instance per service connection; drop it when the service disconnects.
 */
class A11yScreenshotSource internal constructor(
    private val pipeline: ScreenshotPipeline<AccessibilityService.ScreenshotResult, Bitmap>,
) : ScreenshotSource {
    constructor(service: AccessibilityService, config: OcrConfig, clock: Clock) : this(
        ScreenshotPipeline(
            requester = A11yFrameRequester(service),
            ops = BitmapFrameOps,
            limiter = ScreenshotRateLimiter(clock, config.screenshotMinIntervalMs),
            maxSide = config.maxImageSidePx,
        ),
    )

    override suspend fun <T> withScreenshot(
        windowId: Int,
        windowBounds: IntRect,
        block: suspend (Bitmap) -> T,
    ): Shot<T> = pipeline.withScreenshot(windowId, windowBounds, block)
}

/** The system call, chosen by API level. */
internal class A11yFrameRequester(
    private val service: AccessibilityService,
    private val timeoutMs: Long = TIMEOUT_MS,
) : FrameRequester<AccessibilityService.ScreenshotResult> {
    override val coversDisplay: Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    override suspend fun request(windowId: Int): FrameResult<AccessibilityService.ScreenshotResult> =
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                val callback = object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        // A frame that arrives after a cancellation or a timeout is released at once.
                        continuation.resume(FrameResult.Frame(result)) { _, frame, _ ->
                            frame.frame.hardwareBuffer.close()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        continuation.resume(FrameResult.Error(errorCode))
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    service.takeScreenshotOfWindow(windowId, DirectExecutor, callback)
                } else {
                    service.takeScreenshot(Display.DEFAULT_DISPLAY, DirectExecutor, callback)
                }
            }
        } ?: FrameResult.Error(ScreenshotErrors.TIMEOUT)

    /** The callback only resumes the coroutine: it may run on the binder thread. */
    private object DirectExecutor : Executor {
        override fun execute(command: Runnable) = command.run()
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}

/** Bitmap operations of the lifecycle (docs/specs/ocr.md §2). */
internal object BitmapFrameOps : FrameOps<AccessibilityService.ScreenshotResult, Bitmap> {
    override fun width(frame: AccessibilityService.ScreenshotResult): Int = frame.hardwareBuffer.width

    override fun height(frame: AccessibilityService.ScreenshotResult): Int = frame.hardwareBuffer.height

    override fun toSoftware(frame: AccessibilityService.ScreenshotResult): Bitmap? {
        val hardware = Bitmap.wrapHardwareBuffer(frame.hardwareBuffer, frame.colorSpace) ?: return null
        try {
            // Text recognition reads the pixels: a hardware bitmap does not allow it.
            return hardware.copy(Bitmap.Config.ARGB_8888, false)
        } finally {
            hardware.recycle()
        }
    }

    override fun fit(source: Bitmap, fit: ImageFit): Bitmap {
        val matrix = Matrix().apply {
            setScale(fit.width.toFloat() / fit.crop.width, fit.height.toFloat() / fit.crop.height)
        }
        return Bitmap.createBitmap(source, fit.crop.left, fit.crop.top, fit.crop.width, fit.crop.height, matrix, true)
    }

    override fun recycle(image: Bitmap) = image.recycle()

    override fun close(frame: AccessibilityService.ScreenshotResult) = frame.hardwareBuffer.close()
}
