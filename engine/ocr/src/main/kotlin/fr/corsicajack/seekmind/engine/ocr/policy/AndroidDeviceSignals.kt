package fr.corsicajack.seekmind.engine.ocr.policy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import java.util.concurrent.Executor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The device signals of the governor (docs/specs/ocr.md §6): the thermal status listener of
 * [PowerManager], and two receivers registered at run time, never exported (power saving, battery).
 * Call [start] once, [stop] when the service disconnects; [stop] without [start], or twice, does nothing.
 * The signals change from several threads (thermal executor, main thread): every change is atomic.
 */
class AndroidDeviceSignals(private val context: Context, private val executor: Executor) {
    private val power = context.getSystemService(PowerManager::class.java)
    private val signals = MutableStateFlow(DeviceSignals.NORMAL)
    val state: StateFlow<DeviceSignals> = signals.asStateFlow()

    private val thermal = PowerManager.OnThermalStatusChangedListener { status ->
        signals.update { it.copy(thermalThrottled = status >= PowerManager.THERMAL_STATUS_MODERATE) }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = read(intent)
    }

    // Guarded by this.
    private var started = false

    @Synchronized
    fun start() {
        if (started) return
        started = true
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        // ACTION_BATTERY_CHANGED is sticky: the registration returns the last one.
        val sticky = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        val thermalNow = power.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE
        val powerSaveNow = power.isPowerSaveMode
        signals.update { it.copy(thermalThrottled = thermalNow, powerSave = powerSaveNow) }
        sticky?.let(::read)
        power.addThermalStatusListener(executor, thermal)
    }

    /**
     * Reads the device again, without waiting for a broadcast: the battery (sticky broadcast), the
     * battery saver and the heat. On API 34+, the broadcasts of a cached process are queued until it
     * leaves that state, so a process woken for work may see them only afterwards.
     */
    fun refresh() {
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let(::read)
        val thermalNow = power.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE
        signals.update { it.copy(thermalThrottled = thermalNow, powerSave = power.isPowerSaveMode) }
    }

    @Synchronized
    fun stop() {
        if (!started) return
        started = false
        power.removeThermalStatusListener(thermal)
        context.unregisterReceiver(receiver)
    }

    private fun read(intent: Intent) {
        when (intent.action) {
            // update, not a copy of the value: refresh() writes from other threads.
            PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> signals.update { it.copy(powerSave = power.isPowerSaveMode) }

            Intent.ACTION_BATTERY_CHANGED -> {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                // On a power source, whether or not the battery is filling: EXTRA_STATUS can say "charging"
                // after the source is gone (an emulator's battery stays so once unplugged).
                val charging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
                val percent = if (level >= 0 && scale > 0) level * 100 / scale else 100
                signals.update { it.copy(batteryPercent = percent, charging = charging) }
            }
        }
    }
}
