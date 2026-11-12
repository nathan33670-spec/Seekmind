package fr.corsicajack.seekmind.engine.capture

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import dagger.hilt.android.AndroidEntryPoint
import fr.corsicajack.seekmind.engine.capture.routing.EventRouter
import javax.inject.Inject

/**
 * The accessibility service (docs/specs/capture.md §2). It reads, never acts (INV-02): its configuration
 * declares only `canRetrieveWindowContent` and `canTakeScreenshot`. On the main thread it copies the
 * event's primitives and hands them to [EventRouter]; the event object is never kept nor passed on.
 */
@AndroidEntryPoint
class SeekMindAccessibilityService : AccessibilityService() {
    @Inject
    lateinit var router: EventRouter

    override fun onServiceConnected() = router.attach(this)

    override fun onAccessibilityEvent(event: AccessibilityEvent) = router.onEvent(
        event.eventType,
        event.packageName?.toString(),
        event.className?.toString(),
        event.windowId,
    )

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        router.detach()
        return super.onUnbind(intent)
    }
}
