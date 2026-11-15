package fr.corsicajack.seekmind.engine.ocr.screenshot

import fr.corsicajack.seekmind.core.model.Shot

/**
 * Error codes of `AccessibilityService.TakeScreenshotCallback.onFailure`, mapped to [Shot]
 * (docs/specs/ocr.md §2). Values of the framework, repeated here because two of them only exist from
 * API 34 while the mapping must hold on every level.
 */
internal object ScreenshotErrors {
    const val INTERNAL_ERROR = 1
    const val NO_ACCESSIBILITY_ACCESS = 2
    const val INTERVAL_TIME_SHORT = 3
    const val INVALID_DISPLAY = 4
    const val INVALID_WINDOW = 5
    const val SECURE_WINDOW = 6

    /** Our own codes, negative so that they never collide with the system's. */
    const val INVALID_IMAGE = -1
    const val TIMEOUT = -2

    fun toShot(code: Int): Shot<Nothing> = when (code) {
        SECURE_WINDOW -> Shot.SecureWindow
        INTERVAL_TIME_SHORT -> Shot.RateLimited
        else -> Shot.Failed(code)
    }
}
