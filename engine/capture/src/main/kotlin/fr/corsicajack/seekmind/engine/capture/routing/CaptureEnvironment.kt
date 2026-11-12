package fr.corsicajack.seekmind.engine.capture.routing

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import fr.corsicajack.seekmind.core.database.settings.SettingsRepository
import fr.corsicajack.seekmind.engine.capture.status.ServiceConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** What the router needs from the system while the service is connected. */
interface CaptureEnvironment {
    /** Called on the capture thread. */
    interface Listener {
        fun onIgnoredPackages(packages: Set<String>)

        fun onSettings(consented: Boolean, pausedUntil: Long?)
    }

    fun start(service: AccessibilityService, listener: Listener)

    fun stop()
}

/**
 * [CaptureEnvironment] of the service (docs/specs/capture.md §2, §5.3, §8): publishes the connection,
 * computes the ignored packages at connection and on every installation or removal, and follows the
 * settings. All of it on the capture thread: the main thread reads neither disk nor database.
 */
class AndroidCaptureEnvironment(
    private val thread: CaptureThread,
    private val ignoredPackages: IgnoredPackages,
    private val settings: SettingsRepository,
    private val connection: ServiceConnection,
) : CaptureEnvironment {
    private var scope: CoroutineScope? = null
    private var receiver: BroadcastReceiver? = null
    private var context: Context? = null

    override fun start(service: AccessibilityService, listener: CaptureEnvironment.Listener) {
        stop()
        val scope = CoroutineScope(SupervisorJob() + thread.dispatcher).also { scope = it }
        scope.launch { listener.onIgnoredPackages(ignoredPackages.compute()) }
        scope.launch {
            settings.settings.collect { listener.onSettings(it.consent != null, it.pausedUntil) }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                scope.launch { listener.onIgnoredPackages(ignoredPackages.compute()) }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        // Delivered on the capture thread's looper, never on the main thread.
        val handler = Handler(thread.looper)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            service.registerReceiver(receiver, filter, null, handler, Context.RECEIVER_NOT_EXPORTED)
        } else {
            service.registerReceiver(receiver, filter, null, handler)
        }
        this.receiver = receiver
        this.context = service
        connection.setConnected(true)
    }

    override fun stop() {
        receiver?.let { r -> context?.unregisterReceiver(r) }
        receiver = null
        context = null
        scope?.cancel()
        scope = null
        connection.setConnected(false)
    }
}
