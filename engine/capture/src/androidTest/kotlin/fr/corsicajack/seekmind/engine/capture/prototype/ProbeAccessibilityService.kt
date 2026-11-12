package fr.corsicajack.seekmind.engine.capture.prototype

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/** Test-only accessibility service: exposes itself to the tests, reacts to no event. */
class ProbeAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            lastWindowClass = event.className?.toString()
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        var instance: ProbeAccessibilityService? = null

        /** Class name carried by the last TYPE_WINDOW_STATE_CHANGED event (the activity on screen). */
        @Volatile
        var lastWindowClass: String? = null
    }
}
