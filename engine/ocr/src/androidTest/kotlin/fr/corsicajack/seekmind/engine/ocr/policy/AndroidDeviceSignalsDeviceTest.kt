package fr.corsicajack.seekmind.engine.ocr.policy

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertWithMessage
import fr.corsicajack.seekmind.core.model.port.ThrottleReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The governor on the real signals of the device (docs/specs/ocr.md §6), driven by shell commands: each
 * of heat, power saving and a low battery suspends OCR and embeddings, and its end restores them.
 */
@RunWith(AndroidJUnit4::class)
class AndroidDeviceSignalsDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val signals = AndroidDeviceSignals(instrumentation.targetContext) { it.run() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var governor: SignalThermalGovernor

    @Before
    fun setUp() {
        reset()
        signals.start()
        governor = SignalThermalGovernor(signals.state, GovernorRules(), scope)
        awaitReason(null)
    }

    @After
    fun tearDown() {
        signals.stop()
        scope.cancel()
        reset()
    }

    @Test
    fun heat() {
        shell("cmd thermalservice override-status 2")
        awaitReason(ThrottleReason.THERMAL)
        shell("cmd thermalservice override-status 0")
        awaitReason(null)
    }

    @Test
    fun powerSaving() {
        shell("cmd power set-mode 1")
        awaitReason(ThrottleReason.POWER_SAVE)
        shell("cmd power set-mode 0")
        awaitReason(null)
    }

    @Test
    fun lowBattery() {
        shell("dumpsys battery unplug")
        shell("dumpsys battery set level 10")
        awaitReason(ThrottleReason.LOW_BATTERY)
        shell("dumpsys battery set level 80")
        awaitReason(null)
    }

    @Test
    fun stopIsIdempotent() {
        signals.stop()
        signals.stop() // and tearDown stops once more
        AndroidDeviceSignals(instrumentation.targetContext) { it.run() }.stop() // never started
    }

    private fun reset() {
        shell("cmd thermalservice reset")
        shell("cmd power set-mode 0")
        shell("dumpsys battery reset")
    }

    private fun awaitReason(expected: ThrottleReason?) {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        while (governor.state.value.reason != expected && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(POLL_MS)
        }
        val state = governor.state.value
        val message = "signals ${signals.state.value}"
        assertWithMessage(message).that(state.reason).isEqualTo(expected)
        assertWithMessage(message).that(state.allowsOcr).isEqualTo(expected == null)
        assertWithMessage(message).that(state.allowsEmbedding).isEqualTo(expected == null)
    }

    /** Runs [command] and waits for its end (its output is read and dropped). */
    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use {
            it.readBytes()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val POLL_MS = 50L
    }
}
