package fr.corsicajack.seekmind.core.model.port

import kotlinx.coroutines.flow.StateFlow

/** Why the costly work (OCR, embeddings) is suspended (docs/specs/ocr.md §6). */
enum class ThrottleReason { THERMAL, POWER_SAVE, LOW_BATTERY }

/** What the device allows now (docs/specs/ocr.md §6). */
data class GovernorState(
    val allowsOcr: Boolean,
    val allowsEmbedding: Boolean,
    val embeddingThreads: Int,
    /** Null when nothing is suspended; the first reason when several hold. */
    val reason: ThrottleReason?,
)

/**
 * Thermal and energy governor (docs/specs/ocr.md §6), implemented by `engine:ocr` and shared with
 * `engine:index` (embedding batches): the port lives here so that neither engine depends on the other.
 */
interface ThermalGovernor {
    val state: StateFlow<GovernorState>
}
