package fr.corsicajack.seekmind.engine.ocr.screenshot

import fr.corsicajack.seekmind.core.common.IntRect
import fr.corsicajack.seekmind.core.model.Shot

/**
 * The image operations of the lifecycle, behind an interface so that the release order can be tested
 * on the JVM. [F] is the frame returned by the system, [B] a software image.
 */
internal interface FrameOps<F, B> {
    fun width(frame: F): Int

    fun height(frame: F): Int

    /** A software copy of the frame; null when the frame cannot be read. */
    fun toSoftware(frame: F): B?

    /** A new image: [fit] applied to [source]. May return [source] itself. */
    fun fit(source: B, fit: ImageFit): B

    fun recycle(image: B)

    /** Releases the frame's buffer. */
    fun close(frame: F)
}

/**
 * Runs [block] on the software image of [frame], cropped to [cropTo] and reduced to [maxSide]. Every
 * image is released in a `finally`, whatever [block] does (INV-03): the copies are recycled, then the
 * frame is closed. The image never outlives the call.
 */
internal suspend fun <F, B, T> consumeFrame(
    frame: F,
    ops: FrameOps<F, B>,
    cropTo: IntRect?,
    maxSide: Int,
    block: suspend (B) -> T,
): Shot<T> {
    try {
        val fit = ImageFit.plan(ops.width(frame), ops.height(frame), cropTo, maxSide)
            ?: return Shot.Failed(ScreenshotErrors.INVALID_IMAGE)
        val software = ops.toSoftware(frame) ?: return Shot.Failed(ScreenshotErrors.INVALID_IMAGE)
        try {
            if (fit.isIdentity(ops.width(frame), ops.height(frame))) return Shot.Ok(block(software))
            val fitted = ops.fit(software, fit)
            try {
                return Shot.Ok(block(fitted))
            } finally {
                if (fitted !== software) ops.recycle(fitted)
            }
        } finally {
            ops.recycle(software)
        }
    } finally {
        ops.close(frame)
    }
}
