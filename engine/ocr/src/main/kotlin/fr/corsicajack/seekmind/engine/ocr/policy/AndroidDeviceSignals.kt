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

/**
 * The device signals of the governor (docs/specs/ocr.md §6): the thermal status listener of
 * [PowerManager], and two receivers registered at run time, never exported (power saving, battery).
 * Call [start] once, [stop] when the service disconnects.
 */
class AndroidDeviceSignals(private val context: Context, private val executor: Executor) {
    private val power = context.getSystemService(PowerManager::class.java)
    private val signals = MutableStateFlow(DeviceSignals.NORMAL)
    val state: StateFlow<DeviceSignals> = signals.asStateFlow()

    private val thermal = PowerManager.OnThermalStatusChangedListener { status ->
        signals.value = signals.value.copy(thermalThrottled = status >= PowerManager.THERMAL_STATUS_MODERATE)
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = read(intent)
    }

    fun start() {
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
        signals.value = signals.value.copy(
            thermalThrottled = power.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE,
            powerSave = power.isPowerSaveMode,
        )
        sticky?.let(::read)
        power.addThermalStatusListener(executor, thermal)
    }

    fun stop() {
        power.removeThermalStatusListener(thermal)
        context.unregisterReceiver(receiver)
    }

    private fun read(intent: Intent) {
        when (intent.action) {
            PowerManager.ACTION_POWER_SAVE_MODE_CHANGED ->
                signals.value =
                    signals.value.copy(powerSave = power.isPowerSaveMode)

            Intent.ACTION_BATTERY_CHANGED -> {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
                val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
                signals.value = signals.value.copy(
                    batteryPercent = if (level >= 0 && scale > 0) level * 100 / scale else 100,
                    charging = plugged != 0 || status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL,
                )
            }
        }
    }
}
