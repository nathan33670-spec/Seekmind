package fr.corsicajack.seekmind.core.model

/** Decision of the privacy gate (docs/specs/confidentialite.md §2). */
sealed interface GateDecision {
    data object Allow : GateDecision

    data class Deny(val reason: DropReason) : GateDecision
}

/** Text after redaction, and how many spans were replaced by the mark. */
data class Redaction(val text: String, val maskedCount: Int)

/** Result of [fr.corsicajack.seekmind.core.model.port.CaptureSink.ingest] (docs/specs/indexation.md §2). */
sealed interface IngestResult {
    data class Stored(val captureId: Long) : IngestResult

    data class Deduplicated(val captureId: Long) : IngestResult
}
