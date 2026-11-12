package fr.corsicajack.seekmind.engine.capture.prototype

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/** Test-only accessibility service: exposes itself to the tests, reacts to no event. */
class ProbeAccessibilityService : AccessibilityService() {
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
        var instance: ProbeAccessibilityService? = null
    }
}
