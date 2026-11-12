package fr.corsicajack.seekmind.engine.capture.status

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.os.Build
import android.view.accessibility.AccessibilityManager
import fr.corsicajack.seekmind.core.common.Clock
import fr.corsicajack.seekmind.core.database.settings.Settings
import fr.corsicajack.seekmind.core.model.CaptureStatus
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/** Whether the service is connected, as published by the service itself (docs/specs/capture.md §8). */
class ServiceConnection {
    private val state = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = state.asStateFlow()

    fun setConnected(connected: Boolean) {
        state.value = connected
    }
}

/**
 * Whether SeekMind's service is enabled: listed by `AccessibilityManager.getEnabledAccessibilityServiceList`,
 * or connected. The list is read again whenever accessibility, or (API 33+) its services, change.
 */
class AccessibilityServiceState(private val context: Context, private val connection: ServiceConnection) {
    private val listed: Flow<Boolean> = callbackFlow {
        val manager = checkNotNull(context.getSystemService(AccessibilityManager::class.java))
        fun publish() {
            trySend(isListed(manager))
        }
        val stateListener = AccessibilityManager.AccessibilityStateChangeListener { publish() }
        manager.addAccessibilityStateChangeListener(stateListener)
        val servicesListener = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            AccessibilityManager.AccessibilityServicesStateChangeListener { publish() }
                .also { manager.addAccessibilityServicesStateChangeListener(context.mainExecutor, it) }
        } else {
            null
        }
        publish()
        awaitClose {
            manager.removeAccessibilityStateChangeListener(stateListener)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && servicesListener != null) {
                manager.removeAccessibilityServicesStateChangeListener(servicesListener)
            }
        }
    }

    val enabled: Flow<Boolean> = combine(listed, connection.connected) { listed, connected -> listed || connected }
        .distinctUntilChanged()

    private fun isListed(manager: AccessibilityManager): Boolean =
        manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
            val service = it.resolveInfo.serviceInfo
            service.packageName == context.packageName && service.name == SERVICE_CLASS
        }

    private companion object {
        const val SERVICE_CLASS = "fr.corsicajack.seekmind.engine.capture.SeekMindAccessibilityService"
    }
}

/**
 * The capture state shown to the user (docs/specs/capture.md §8, EF-CAP-05), from the service state, the
 * consent and the pause. The end of a scheduled pause is seen at the next change of an input (the
 * timer that ends it arrives with M2-07).
 */
class CaptureStatusRepository(serviceEnabled: Flow<Boolean>, settings: Flow<Settings>, private val clock: Clock) {
    private val lastCaptureAt = MutableStateFlow<Long?>(null)

    val status: Flow<CaptureStatus> = combine(serviceEnabled, settings, lastCaptureAt) { enabled, current, last ->
        resolve(enabled, current.consent != null, current.pausedUntil, clock.nowMillis(), last)
    }.distinctUntilChanged()

    /** Called by the pipeline when a capture is stored (M2-06). */
    fun onCaptureStored(at: Long) {
        lastCaptureAt.value = at
    }

    companion object {
        /**
         * Consent comes first: without it, the interface reopens the disclosure, before any trip to the
         * system settings (docs/05-conformite-play-store.md §4.1). Then the service, then the pause.
         */
        fun resolve(
            serviceEnabled: Boolean,
            consented: Boolean,
            pausedUntil: Long?,
            now: Long,
            lastCaptureAt: Long?,
        ): CaptureStatus = when {
            !consented -> CaptureStatus.ConsentMissing

            !serviceEnabled -> CaptureStatus.ServiceDisabled

            pausedUntil != null && now < pausedUntil ->
                CaptureStatus.Paused(until = pausedUntil.takeIf { it != Long.MAX_VALUE })

            else -> CaptureStatus.Active(lastCaptureAt)
        }
    }
}
