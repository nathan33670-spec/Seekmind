package fr.corsicajack.seekmind.engine.capture.routing

import android.accessibilityservice.AccessibilityService
import fr.corsicajack.seekmind.core.common.Clock
import fr.corsicajack.seekmind.engine.capture.pipeline.ConnectedService

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
    /** The service for the pipeline's reads (docs/specs/capture.md §6). */
    private val connected: ConnectedService = ConnectedService(),
) {
    /** Gate on the settings: consent recorded, and the pause end, if any. */
    private class Gate(val consented: Boolean, val pausedUntil: Long?) {
        fun allows(now: Long) = consented && (pausedUntil == null || now >= pausedUntil)
    }

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
            val next = Gate(consented, pausedUntil)
            gate = next
            // A stable screen already scheduled must not fire into a pause or after consent is withdrawn.
            if (!next.allows(clock.nowMillis())) executor.execute(loop::reset)
        }
    }

    /** The packages never read (§5.3), for the privacy gate; empty until the service is connected. */
    fun ignoredPackages(): Set<String> = ignored.orEmpty()

    fun attach(service: AccessibilityService) {
        connected.service = service
        environment.start(service, listener)
    }

    fun detach() {
        connected.service = null
        environment.stop()
        ignored = null
        gate = null
        executor.execute(loop::reset)
    }

    fun onEvent(type: Int, packageName: String?, className: String?, windowId: Int) {
        received++
        if (!accepts(packageName)) return
        forwarded++
        executor.execute { loop.onEvent(type, packageName!!, className, windowId) }
    }

    /**
     * Whether an event would pass now: the ignored set and the settings are known, consent given, no
     * pause. The settings reach the router through a flow, a little after they are written: an end-to-end
     * test waits for this before opening a screen, or the screen's events may meet the gate still closed.
     */
    fun isOpen(): Boolean = ignored != null && gate?.allows(clock.nowMillis()) == true

    /**
     * Diagnostics, counts and states only (INV-07): what became of the events since the start. Read by
     * the end-to-end tests when an expected event does not come. Approximate: written on the main thread,
     * read from another one.
     */
    fun diagnostics(): String = "router received=$received forwarded=$forwarded no_package=$noPackage " +
        "no_ignored_set=$noIgnoredSet ignored=$ignoredDrops no_gate=$noGate gate_closed=$gateClosed " +
        "gate=${gate?.let { "consented=${it.consented},paused=${it.pausedUntil != null}" } ?: "none"} " +
        "connected=${connected.service != null}"

    @Volatile private var received = 0L

    @Volatile private var forwarded = 0L

    @Volatile private var noPackage = 0L

    @Volatile private var noIgnoredSet = 0L

    @Volatile private var ignoredDrops = 0L

    @Volatile private var noGate = 0L

    @Volatile private var gateClosed = 0L

    private fun accepts(packageName: String?): Boolean {
        if (packageName == null) return false.also { noPackage++ }
        val ignored = ignored ?: return false.also { noIgnoredSet++ }
        if (packageName in ignored) return false.also { ignoredDrops++ }
        val gate = gate ?: return false.also { noGate++ }
        return gate.allows(clock.nowMillis()).also { if (!it) gateClosed++ }
    }
}
