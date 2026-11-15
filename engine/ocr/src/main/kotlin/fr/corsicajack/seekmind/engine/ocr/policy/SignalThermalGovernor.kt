package fr.corsicajack.seekmind.engine.ocr.policy

import fr.corsicajack.seekmind.core.model.port.GovernorState
import fr.corsicajack.seekmind.core.model.port.ThermalGovernor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** [ThermalGovernor] on a flow of [DeviceSignals]: the Android one ([AndroidDeviceSignals]), or simulated in tests. */
class SignalThermalGovernor(
    private val signals: StateFlow<DeviceSignals>,
    private val rules: GovernorRules,
    scope: CoroutineScope,
    /** Reads the device again ([AndroidDeviceSignals.refresh]); nothing for simulated signals. */
    private val refresh: () -> Unit = {},
) : ThermalGovernor {
    override val state: StateFlow<GovernorState> =
        signals.map(rules::state).stateIn(scope, SharingStarted.Eagerly, rules.state(signals.value))

    override fun current(): GovernorState {
        refresh()
        return rules.state(signals.value)
    }
}
