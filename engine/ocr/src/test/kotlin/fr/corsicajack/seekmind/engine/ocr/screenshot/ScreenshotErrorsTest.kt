package fr.corsicajack.seekmind.engine.ocr.screenshot

import android.accessibilityservice.AccessibilityService
import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.model.Shot
import org.junit.Test

class ScreenshotErrorsTest {
    @Test
    fun `our constants are the framework's`() {
        assertThat(ScreenshotErrors.INTERNAL_ERROR).isEqualTo(AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR)
        assertThat(ScreenshotErrors.NO_ACCESSIBILITY_ACCESS)
            .isEqualTo(AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS)
        assertThat(ScreenshotErrors.INTERVAL_TIME_SHORT)
            .isEqualTo(AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT)
        assertThat(
            ScreenshotErrors.INVALID_DISPLAY,
        ).isEqualTo(AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY)
        assertThat(ScreenshotErrors.INVALID_WINDOW).isEqualTo(AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_WINDOW)
        assertThat(ScreenshotErrors.SECURE_WINDOW).isEqualTo(AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW)
    }

    @Test
    fun `a secure window maps to SecureWindow`() {
        assertThat(ScreenshotErrors.toShot(ScreenshotErrors.SECURE_WINDOW)).isEqualTo(Shot.SecureWindow)
    }

    @Test
    fun `a too short interval maps to RateLimited`() {
        assertThat(ScreenshotErrors.toShot(ScreenshotErrors.INTERVAL_TIME_SHORT)).isEqualTo(Shot.RateLimited)
    }

    @Test
    fun `other codes map to Failed with the code`() {
        for (code in listOf(
            ScreenshotErrors.INTERNAL_ERROR,
            ScreenshotErrors.NO_ACCESSIBILITY_ACCESS,
            ScreenshotErrors.INVALID_DISPLAY,
            ScreenshotErrors.INVALID_WINDOW,
            ScreenshotErrors.TIMEOUT,
            42,
        )) {
            assertThat(ScreenshotErrors.toShot(code)).isEqualTo(Shot.Failed(code))
        }
    }
}
