package fr.corsicajack.seekmind.lock

import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.testing.FakeClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

class AppLockTest {
    private val clock = FakeClock()
    private val settings = MutableStateFlow(LockSettings(enabled = true, graceMs = GRACE))
    private var secure = true
    private val lock = AppLock(settings, clock) { secure }

    private suspend fun state() = lock.state.first()

    @Test
    fun `the lock applies at start when enabled on a secure device`() = runTest {
        lock.onForeground()
        assertThat(state()).isEqualTo(LockState.LOCKED)
    }

    @Test
    fun `an authentication unlocks`() = runTest {
        lock.onForeground()
        lock.onAuthenticated()
        assertThat(state()).isEqualTo(LockState.UNLOCKED)
    }

    @Test
    fun `back before the grace delay needs no new authentication`() = runTest {
        lock.onForeground()
        lock.onAuthenticated()
        lock.onBackground()
        clock.advance(GRACE - 1)
        lock.onForeground()
        assertThat(state()).isEqualTo(LockState.UNLOCKED)
    }

    @Test
    fun `back after the grace delay needs an authentication`() = runTest {
        lock.onForeground()
        lock.onAuthenticated()
        lock.onBackground()
        clock.advance(GRACE)
        lock.onForeground()
        assertThat(state()).isEqualTo(LockState.LOCKED)

        lock.onAuthenticated()
        assertThat(state()).isEqualTo(LockState.UNLOCKED)
    }

    @Test
    fun `the grace delay is the one of the settings`() = runTest {
        settings.value = settings.value.copy(graceMs = 0)
        lock.onAuthenticated()
        lock.onBackground()
        lock.onForeground()
        assertThat(state()).isEqualTo(LockState.LOCKED)
    }

    @Test
    fun `while in the background the state does not change`() = runTest {
        lock.onAuthenticated()
        lock.onBackground()
        clock.advance(10 * GRACE)
        // Only coming back counts: the screen is not shown in the meantime.
        assertThat(state()).isEqualTo(LockState.UNLOCKED)
    }

    @Test
    fun `a disabled lock never locks`() = runTest {
        settings.value = settings.value.copy(enabled = false)
        lock.onForeground()
        assertThat(state()).isEqualTo(LockState.UNLOCKED)
        lock.onBackground()
        clock.advance(10 * GRACE)
        lock.onForeground()
        assertThat(state()).isEqualTo(LockState.UNLOCKED)
    }

    @Test
    fun `without a secure screen lock the application stays open`() = runTest {
        secure = false
        val open = AppLock(settings, clock) { secure }
        open.onForeground()
        assertThat(open.state.first()).isEqualTo(LockState.UNLOCKED)
        assertThat(open.isDeviceSecure()).isFalse()
    }

    @Test
    fun `setting a screen lock while the application is open locks it at the next return`() = runTest {
        secure = false
        val open = AppLock(settings, clock) { secure }
        open.onForeground()
        assertThat(open.state.first()).isEqualTo(LockState.UNLOCKED)

        open.onBackground()
        secure = true
        open.onForeground()
        assertThat(open.state.first()).isEqualTo(LockState.LOCKED)
    }

    private companion object {
        const val GRACE = 5 * 60_000L
    }
}
