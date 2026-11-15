package fr.corsicajack.seekmind.engine.ocr.policy

import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.model.port.ThrottleReason
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** docs/specs/ocr.md §6: each signal suspends OCR and embeddings, and its end restores them. */
@OptIn(ExperimentalCoroutinesApi::class)
class ThermalGovernorTest {
    private val signals = MutableStateFlow(DeviceSignals.NORMAL)

    private fun check(signal: DeviceSignals, reason: ThrottleReason) = runTest(UnconfinedTestDispatcher()) {
        val governor = SignalThermalGovernor(signals, GovernorRules(), backgroundScope)
        assertThat(governor.state.value.allowsOcr).isTrue()
        assertThat(governor.state.value.reason).isNull()

        signals.value = signal
        assertThat(governor.state.value.allowsOcr).isFalse()
        assertThat(governor.state.value.allowsEmbedding).isFalse()
        assertThat(governor.state.value.reason).isEqualTo(reason)

        signals.value = DeviceSignals.NORMAL
        assertThat(governor.state.value.allowsOcr).isTrue()
        assertThat(governor.state.value.allowsEmbedding).isTrue()
        assertThat(governor.state.value.reason).isNull()
    }

    @Test
    fun `current reads the device again before deciding`() = runTest(UnconfinedTestDispatcher()) {
        var refreshes = 0
        // A broadcast that has not arrived: only the refresh sees the low battery.
        val governor = SignalThermalGovernor(signals, GovernorRules(), backgroundScope) {
            refreshes++
            signals.value = DeviceSignals.NORMAL.copy(batteryPercent = 10)
        }
        assertThat(governor.state.value.reason).isNull()
        assertThat(governor.current().reason).isEqualTo(ThrottleReason.LOW_BATTERY)
        assertThat(refreshes).isEqualTo(1)
    }

    @Test
    fun heat() = check(DeviceSignals.NORMAL.copy(thermalThrottled = true), ThrottleReason.THERMAL)

    @Test
    fun powerSaving() = check(DeviceSignals.NORMAL.copy(powerSave = true), ThrottleReason.POWER_SAVE)

    @Test
    fun lowBattery() = check(DeviceSignals.NORMAL.copy(batteryPercent = 14), ThrottleReason.LOW_BATTERY)

    @Test
    fun aLowBatteryOnChargeSuspendsNothingAndChargingGivesFourThreads() {
        val rules = GovernorRules()
        val charging = rules.state(DeviceSignals.NORMAL.copy(batteryPercent = 5, charging = true))
        assertThat(charging.allowsOcr).isTrue()
        assertThat(charging.embeddingThreads).isEqualTo(4)
        assertThat(rules.state(DeviceSignals.NORMAL).embeddingThreads).isEqualTo(2)
        assertThat(rules.state(DeviceSignals.NORMAL.copy(batteryPercent = 15)).allowsOcr).isTrue()
    }

    @Test
    fun heatComesFirstWhenSeveralSignalsHold() {
        val state = GovernorRules().state(
            DeviceSignals(thermalThrottled = true, powerSave = true, batteryPercent = 3, charging = false),
        )
        assertThat(state.reason).isEqualTo(ThrottleReason.THERMAL)
    }
}
