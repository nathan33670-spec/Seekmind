package fr.corsicajack.seekmind.lock

import fr.corsicajack.seekmind.core.common.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update

/** What the interface may show (docs/specs/ui-ux.md §3.6). */
enum class LockState {
    /** Settings not read yet: nothing is composed but the neutral screen. */
    PENDING,

    /** Authentication required: only the neutral screen is composed. */
    LOCKED,

    /** Content allowed. */
    UNLOCKED,
}

/** The two lock settings (`Settings.lockEnabled`, `Settings.lockGraceMs`). */
data class LockSettings(val enabled: Boolean, val graceMs: Long)

/** Whether the device has a secure screen lock (PIN, pattern, password). */
fun interface DeviceSecurity {
    fun isSecure(): Boolean
}

/**
 * Application lock (EF-PRIV-10, ticket M6-07). The lock applies when it is enabled and the device has
 * a secure screen lock; it is lifted by an authentication and comes back when the application returns
 * to the foreground after at least the grace delay in the background, or when the process restarts.
 *
 * The host activity reports [onForeground] and [onBackground]; configuration changes are not visits
 * to the background and must not be reported.
 */
class AppLock(private val settings: Flow<LockSettings>, private val clock: Clock, private val device: DeviceSecurity) {
    private data class Session(
        val authenticated: Boolean,
        val deviceSecure: Boolean,
        val backgroundAt: Long? = null,
        val foregroundAt: Long? = null,
    )

    private val session = MutableStateFlow(Session(authenticated = false, deviceSecure = device.isSecure()))

    val state: Flow<LockState> = combine(settings, session) { lock, current ->
        val background = current.backgroundAt
        val foreground = current.foregroundAt
        val expired = background != null && foreground != null && foreground - background >= lock.graceMs
        when {
            !lock.enabled || !current.deviceSecure -> LockState.UNLOCKED
            current.authenticated && !expired -> LockState.UNLOCKED
            else -> LockState.LOCKED
        }
    }.distinctUntilChanged()

    /** Whether the lock can be used at all; the setting is disabled otherwise. */
    fun isDeviceSecure(): Boolean = device.isSecure()

    fun onBackground() {
        val now = clock.elapsedRealtime()
        session.update { it.copy(backgroundAt = now, foregroundAt = null) }
    }

    fun onForeground() {
        val now = clock.elapsedRealtime()
        val secure = device.isSecure()
        session.update { it.copy(foregroundAt = if (it.backgroundAt != null) now else null, deviceSecure = secure) }
    }

    /** A successful authentication, or the user turning the lock on from an open session. */
    fun onAuthenticated() {
        session.update { it.copy(authenticated = true, backgroundAt = null, foregroundAt = null) }
    }
}
