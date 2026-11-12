package fr.corsicajack.seekmind.engine.capture.routing

import android.view.accessibility.AccessibilityEvent
import fr.corsicajack.seekmind.core.common.Clock
import fr.corsicajack.seekmind.core.common.SmLog
import fr.corsicajack.seekmind.core.model.PipelineEvent
import fr.corsicajack.seekmind.core.model.config.CaptureConfig
import fr.corsicajack.seekmind.engine.capture.StableScreenHandler
import fr.corsicajack.seekmind.engine.capture.meta.ActivityTracker
import fr.corsicajack.seekmind.engine.capture.stability.Debouncer
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch

/** The foreground window: what the adaptive slowdown is about (docs/specs/capture.md §3.2). */
data class WindowKey(val packageName: String, val windowId: Int)

/**
 * The capture thread's side of the routing (docs/specs/capture.md §2, §3): follows the current
 * window and its activity, re-arms the debouncer, and hands a stable screen to [handler]. Every method
 * runs on [executor]'s thread.
 */
class CaptureLoop(
    config: CaptureConfig,
    clock: Clock,
    private val executor: CaptureExecutor,
    private val activities: ActivityTracker,
    private val handler: StableScreenHandler?,
) {
    private val scope = CoroutineScope(SupervisorJob() + executor.dispatcher)
    private val debouncer = Debouncer(config, clock, executor.newTimer(), ::onStable)
    private var window: WindowKey? = null

    fun onEvent(type: Int, packageName: String, className: String?, windowId: Int) {
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            activities.onWindowStateChanged(packageName, className)
            WindowKey(packageName, windowId).let {
                window = it
                debouncer.onWindowChanged(it)
            }
        }
        debouncer.onEvent()
    }

    /** An application was installed, updated or removed: activity answers may have changed. */
    fun onPackagesChanged() = executor.execute(activities::clearCache)

    /** The service is gone: nothing pending, no capture running. */
    fun reset() {
        scope.coroutineContext.cancelChildren()
        debouncer.reset()
        window = null
    }

    private fun onStable() {
        val current = window ?: NO_WINDOW
        scope.launch {
            val event = try {
                handler?.onStable()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The pipeline catches its own failures (§6); this only protects the loop.
                SmLog.w(TAG, "handler_error", e)
                null
            }
            debouncer.onCaptureFinished(current, deduplicated = event is PipelineEvent.Deduplicated)
        }
    }

    private companion object {
        const val TAG = "CaptureLoop"
        val NO_WINDOW = WindowKey("", -1)
    }
}
