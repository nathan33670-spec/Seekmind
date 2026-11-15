package fr.corsicajack.seekmind.engine.ocr.policy

import fr.corsicajack.seekmind.core.model.config.EmbeddingConfig
import fr.corsicajack.seekmind.core.model.config.OcrConfig
import fr.corsicajack.seekmind.core.model.port.GovernorState
import fr.corsicajack.seekmind.core.model.port.ThrottleReason

/** The signals the governor reads (docs/specs/ocr.md §6). */
data class DeviceSignals(
    /** Thermal status at `THERMAL_STATUS_MODERATE` or above. */
    val thermalThrottled: Boolean,
    val powerSave: Boolean,
    /** 0 to 100; 100 when unknown. */
    val batteryPercent: Int,
    val charging: Boolean,
) {
    companion object {
        val NORMAL = DeviceSignals(thermalThrottled = false, powerSave = false, batteryPercent = 100, charging = false)
    }
}

/**
 * The state of the governor for given signals (docs/specs/ocr.md §6): each of heat, power saving and a
 * battery under [OcrConfig.lowBatteryPercent] off charge suspends OCR and embeddings; on charge, the
 * embeddings get [EmbeddingConfig.threadsCharging] threads.
 */
class GovernorRules(
    private val ocr: OcrConfig = OcrConfig(),
    private val embedding: EmbeddingConfig = EmbeddingConfig(),
) {
    fun state(signals: DeviceSignals): GovernorState {
        val reason = when {
            signals.thermalThrottled -> ThrottleReason.THERMAL
            signals.powerSave -> ThrottleReason.POWER_SAVE
            !signals.charging && signals.batteryPercent < ocr.lowBatteryPercent -> ThrottleReason.LOW_BATTERY
            else -> null
        }
        return GovernorState(
            allowsOcr = reason == null,
            allowsEmbedding = reason == null,
            embeddingThreads = if (signals.charging) embedding.threadsCharging else embedding.threadsDefault,
            reason = reason,
        )
    }
}
