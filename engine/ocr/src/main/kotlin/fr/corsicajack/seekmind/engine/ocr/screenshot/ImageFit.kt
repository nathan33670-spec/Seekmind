package fr.corsicajack.seekmind.engine.ocr.screenshot

import fr.corsicajack.seekmind.core.common.IntRect
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * How a screenshot becomes the image handed to the block: [crop] in the source's pixels, then a
 * reduction to [width] × [height] (docs/specs/ocr.md §2).
 */
internal data class ImageFit(val crop: IntRect, val width: Int, val height: Int) {
    /** True when the source can be used as it is. */
    fun isIdentity(sourceWidth: Int, sourceHeight: Int): Boolean =
        crop == IntRect(0, 0, sourceWidth, sourceHeight) && width == sourceWidth && height == sourceHeight

    companion object {
        /**
         * Plans the crop of a [sourceWidth] × [sourceHeight] image to [cropTo] (clamped to the image;
         * null keeps the whole image), and the reduction so that the longest side is at most [maxSide].
         * Null when nothing of the image is left.
         */
        fun plan(sourceWidth: Int, sourceHeight: Int, cropTo: IntRect?, maxSide: Int): ImageFit? {
            require(maxSide > 0) { "maxSide must be positive" }
            if (sourceWidth <= 0 || sourceHeight <= 0) return null
            val crop = cropTo?.let {
                IntRect(
                    left = max(it.left, 0),
                    top = max(it.top, 0),
                    right = min(it.right, sourceWidth),
                    bottom = min(it.bottom, sourceHeight),
                )
            } ?: IntRect(0, 0, sourceWidth, sourceHeight)
            if (crop.width <= 0 || crop.height <= 0) return null
            val longest = max(crop.width, crop.height)
            if (longest <= maxSide) return ImageFit(crop, crop.width, crop.height)
            val scale = maxSide.toDouble() / longest
            return ImageFit(
                crop = crop,
                width = (crop.width * scale).roundToInt().coerceIn(1, maxSide),
                height = (crop.height * scale).roundToInt().coerceIn(1, maxSide),
            )
        }
    }
}
