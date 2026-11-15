package fr.corsicajack.seekmind.engine.capture.pipeline

import fr.corsicajack.seekmind.core.model.ProbeResult
import fr.corsicajack.seekmind.core.model.ScreenMeta
import fr.corsicajack.seekmind.core.model.TreeExtraction
import fr.corsicajack.seekmind.core.model.port.SecureWindowProbe
import fr.corsicajack.seekmind.engine.ocr.policy.OcrPolicy

/**
 * Step 6 of the pipeline (docs/specs/capture.md §6, ocr.md §3, §5): the OCR policy decides whether the
 * image is needed, then the probe takes a single screenshot that serves both. A secure window, or an
 * `Unknown` probe of a listed app, rejects the capture; any other `Unknown` lets it go on without OCR.
 */
class ProbeScreenshotStep(private val probe: SecureWindowProbe, private val policy: OcrPolicy) : ScreenshotStep {
    override suspend fun run(meta: ScreenMeta, extraction: TreeExtraction, treeSimHash: Long): ScreenshotOutcome {
        val needImage = policy.shouldOcr(meta, extraction, treeSimHash)
        val result = probe.probe(meta, extraction.windowBounds, needImage)
        if (probe.rejects(meta, result)) return ScreenshotOutcome.Secure
        return ScreenshotOutcome.Checked((result as? ProbeResult.NotSecure)?.ocr?.lines)
    }
}
