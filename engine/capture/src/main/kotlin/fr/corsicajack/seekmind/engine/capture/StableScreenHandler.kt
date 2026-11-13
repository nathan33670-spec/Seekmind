package fr.corsicajack.seekmind.engine.capture

import fr.corsicajack.seekmind.core.model.PipelineEvent

/**
 * What a stable screen triggers, on the capture thread: the capture pipeline (docs/specs/capture.md §6,
 * ticket M2-06). Until it is bound, a stable screen reads nothing.
 */
fun interface StableScreenHandler {
    /** Captures the active window; never throws. */
    suspend fun onStable(): PipelineEvent
}
