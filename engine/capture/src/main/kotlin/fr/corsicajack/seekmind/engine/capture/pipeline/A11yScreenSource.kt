package fr.corsicajack.seekmind.engine.capture.pipeline

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import fr.corsicajack.seekmind.core.common.Outcome
import fr.corsicajack.seekmind.core.model.DropReason
import fr.corsicajack.seekmind.engine.capture.tree.A11yUiNode
import fr.corsicajack.seekmind.engine.capture.tree.ActiveWindow

/**
 * The service while it is connected: set by the router at connection, cleared at disconnection, read
 * by the pipeline on the capture thread.
 */
class ConnectedService {
    @Volatile
    var service: AccessibilityService? = null
}

/** [ScreenSource] on the connected accessibility service (docs/specs/capture.md §6, steps 1 and 2). */
class A11yScreenSource(context: Context, private val connected: ConnectedService) : ScreenSource {
    private val keyguard = context.getSystemService(KeyguardManager::class.java)

    override fun isDeviceLocked(): Boolean = keyguard.isKeyguardLocked

    override fun activeWindow(): Outcome<ActiveWindow, DropReason> {
        val service = connected.service ?: return Outcome.Failure(DropReason.NO_ROOT)
        return A11yUiNode.activeWindow(service)
    }
}
