package fr.corsicajack.seekmind.engine.ocr.policy

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.ParcelFileDescriptor
import android.os.PowerManager
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
 * of heat, power saving and a low battery suspends OCR and embeddings, and its end restores them. Read
 * with [SignalThermalGovernor.current], as the decisions are.
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
        governor = SignalThermalGovernor(signals.state, GovernorRules(), scope, signals::refresh)
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
        // Android refuses the battery saver on a power source: wait until the device has seen the unplug.
        // Above 90 %, Android turns a sticky battery saver off again once unplugged
        // (`low_power_sticky_auto_disable_level`): the level is set below it, above the low-battery one.
        shell("dumpsys battery unplug")
        shell("dumpsys battery set status 3") // BATTERY_STATUS_DISCHARGING
        shell("dumpsys battery set level 50")
        awaitSignals("unplugged at 50 %") { !it.charging && it.batteryPercent == 50 }
        // `cmd power set-mode` has no effect on some images (API 36): the global setting is what the
        // power manager follows, and it sends ACTION_POWER_SAVE_MODE_CHANGED.
        shell("cmd power set-mode 1")
        shell("settings put global low_power 1")
        awaitReason(ThrottleReason.POWER_SAVE)
        shell("cmd power set-mode 0")
        shell("settings put global low_power 0")
        awaitReason(null)
    }

    @Test
    fun lowBattery() {
        shell("dumpsys battery unplug")
        shell("dumpsys battery set status 3") // BATTERY_STATUS_DISCHARGING
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
        shell("settings put global low_power 0")
        shell("dumpsys battery reset")
    }

    private fun awaitReason(expected: ThrottleReason?) {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        // current(), as the OCR policy and the embedding batches decide: the broadcasts of the test
        // process can be queued while it counts as cached (API 34+).
        while (governor.current().reason != expected && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(POLL_MS)
        }
        val state = governor.current()
        val message = if (state.reason == expected) "" else diagnosis()
        assertWithMessage(message).that(state.reason).isEqualTo(expected)
        assertWithMessage(message).that(state.allowsOcr).isEqualTo(expected == null)
        assertWithMessage(message).that(state.allowsEmbedding).isEqualTo(expected == null)
    }

    private fun awaitSignals(what: String, condition: (DeviceSignals) -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        while (!condition(signals.state.value) && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(POLL_MS)
            signals.refresh()
        }
        assertWithMessage("$what: ${diagnosis()}").that(condition(signals.state.value)).isTrue()
    }

    /** Runs [command] and waits for its end; returns its output. */
    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use {
            it.readBytes().decodeToString()
        }

    /**
     * What the device itself says, next to what the receiver saw: the last battery broadcast (sticky),
     * the power manager, and the battery service. No captured content (INV-07).
     */
    private fun diagnosis(): String {
        val sticky = instrumentation.targetContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val power = instrumentation.targetContext.getSystemService(PowerManager::class.java)
        val battery = shell("dumpsys battery").lines().filter { line ->
            listOf("powered", "status", "level").any { line.contains(it, ignoreCase = true) }
        }.joinToString("; ") { it.trim() }
        return "signals ${signals.state.value}; sticky plugged=${sticky?.getIntExtra(
            BatteryManager.EXTRA_PLUGGED,
            -1,
        )} " +
            "level=${sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)}; powerSave=${power.isPowerSaveMode}; " +
            "low_power=${shell("settings get global low_power").trim()}; battery: $battery"
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val POLL_MS = 50L
    }
}
