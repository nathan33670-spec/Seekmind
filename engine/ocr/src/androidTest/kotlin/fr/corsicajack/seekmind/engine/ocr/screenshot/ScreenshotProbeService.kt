package fr.corsicajack.seekmind.engine.ocr.screenshot

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/** Test-only accessibility service: exposes itself to the tests, reacts to no event. */
class ScreenshotProbeService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        var instance: ScreenshotProbeService? = null
    }
}
