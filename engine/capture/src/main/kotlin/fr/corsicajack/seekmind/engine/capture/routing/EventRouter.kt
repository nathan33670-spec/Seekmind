package fr.corsicajack.seekmind.engine.capture.routing

import android.accessibilityservice.AccessibilityService
import fr.corsicajack.seekmind.core.common.Clock

/**
 * Entry point of the accessibility events (docs/specs/capture.md §2). Runs on the main thread, where
 * it only reads two volatile fields and compares primitives: events of the always-ignored packages
 * (§5.3), events without consent and events during a pause (§7) are dropped there and never wake the
 * capture thread; the others are handed to [CaptureLoop] on it. Nothing passes before the first
 * ignored set and the first settings are known.
 */
class EventRouter(
    private val executor: CaptureExecutor,
    private val loop: CaptureLoop,
    private val environment: CaptureEnvironment,
    private val clock: Clock,
) {
    /** Gate on the settings: consent recorded, and the pause end, if any. */
    private class Gate(val consented: Boolean, val pausedUntil: Long?)

    @Volatile
    private var ignored: Set<String>? = null

    @Volatile
    private var gate: Gate? = null

    private val listener = object : CaptureEnvironment.Listener {
        override fun onIgnoredPackages(packages: Set<String>) {
            ignored = packages
            loop.onPackagesChanged()
        }

        override fun onSettings(consented: Boolean, pausedUntil: Long?) {
            gate = Gate(consented, pausedUntil)
        }
    }

    fun attach(service: AccessibilityService) {
        environment.start(service, listener)
    }

    fun detach() {
        environment.stop()
        ignored = null
        gate = null
        executor.execute(loop::reset)
    }

    fun onEvent(type: Int, packageName: String?, className: String?, windowId: Int) {
        if (!accepts(packageName)) return
        executor.execute { loop.onEvent(type, packageName!!, className, windowId) }
    }

    private fun accepts(packageName: String?): Boolean {
        if (packageName == null) return false
        val ignored = ignored ?: return false
        if (packageName in ignored) return false
        val gate = gate ?: return false
        if (!gate.consented) return false
        val pausedUntil = gate.pausedUntil
        return pausedUntil == null || clock.nowMillis() >= pausedUntil
    }
}
