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
            event.className?.toString()?.let(seenWindowClasses::add)
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

        /**
         * Class names carried by TYPE_WINDOW_STATE_CHANGED events since the tests last cleared it.
         * Several events follow an activity start, and the last one may name a view container.
         */
        val seenWindowClasses: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    }
}
