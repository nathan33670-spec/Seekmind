package fr.corsicajack.seekmind.engine.capture.pipeline

import fr.corsicajack.seekmind.core.common.Clock
import fr.corsicajack.seekmind.core.common.Outcome
import fr.corsicajack.seekmind.core.common.SmLog
import fr.corsicajack.seekmind.core.model.DropReason
import fr.corsicajack.seekmind.core.model.GateDecision
import fr.corsicajack.seekmind.core.model.IngestResult
import fr.corsicajack.seekmind.core.model.OcrLine
import fr.corsicajack.seekmind.core.model.PipelineEvent
import fr.corsicajack.seekmind.core.model.RawCapture
import fr.corsicajack.seekmind.core.model.ScreenMeta
import fr.corsicajack.seekmind.core.model.TextLine
import fr.corsicajack.seekmind.core.model.TextSource
import fr.corsicajack.seekmind.core.model.TreeExtraction
import fr.corsicajack.seekmind.core.model.config.CaptureConfig
import fr.corsicajack.seekmind.core.model.port.CaptureSink
import fr.corsicajack.seekmind.core.model.port.PrivacyGate
import fr.corsicajack.seekmind.engine.capture.StableScreenHandler
import fr.corsicajack.seekmind.engine.capture.meta.ScreenMetaReader
import fr.corsicajack.seekmind.engine.capture.tree.ActiveWindow
import fr.corsicajack.seekmind.engine.capture.tree.TreeExtractor
import fr.corsicajack.seekmind.engine.capture.tree.UiNode
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** The service's side of the pipeline: what is on screen, and whether the device is locked. */
interface ScreenSource {
    fun isDeviceLocked(): Boolean

    /**
     * The application window in front, whose root the pipeline releases; [DropReason.NO_ROOT] without
     * a connected service or a root, [DropReason.NOT_APP_WINDOW] for another kind of window.
     */
    fun activeWindow(): Outcome<ActiveWindow, DropReason>
}

/** What step 6 found: a secure window, or the lines recognised by the OCR (null when none ran). */
sealed interface ScreenshotOutcome {
    data object Secure : ScreenshotOutcome

    data class Checked(val ocrLines: List<OcrLine>?) : ScreenshotOutcome
}

/**
 * Step 6 (docs/specs/capture.md §6, ocr.md §3): the secure-window probe and, when the OCR policy asks
 * for it, the OCR, on a single screenshot ([ProbeScreenshotStep]); [NotProbed] probes nothing.
 */
fun interface ScreenshotStep {
    suspend fun run(meta: ScreenMeta, extraction: TreeExtraction, treeSimHash: Long): ScreenshotOutcome

    companion object {
        /** No probe, no OCR. */
        val NotProbed = ScreenshotStep { _, _, _ -> ScreenshotOutcome.Checked(ocrLines = null) }
    }
}

/** The lines of a screen once merged, and their origin (bits of [TextSource]). */
data class MergedLines(val lines: List<String>, val textSource: Int)

/** Merges the tree's lines with the OCR's (step 7, docs/specs/ocr.md §7). */
fun interface LineMerger {
    fun merge(tree: List<TextLine>, ocr: List<OcrLine>): MergedLines

    companion object {
        /** The OCR lines after the tree's, without deduplication: for tests. */
        val Append = LineMerger { tree, ocr ->
            MergedLines(tree.map { it.text } + ocr.map { it.text }, TextSource.TREE or TextSource.OCR)
        }
    }
}

/** The settings the pipeline checks first (step 1). */
data class CaptureGate(val consented: Boolean, val pausedUntil: Long?)

/**
 * The capture pipeline (docs/specs/capture.md §6), run on the capture thread for each stable screen.
 * Steps in the order of the spec, the first failure ends the capture: nothing of the screen is read
 * before the package is accepted (step 3), nothing is stored before the gate (INV-05). Every exception is
 * caught here and counted as [DropReason.ERROR]; every outcome is counted ([outcomes]) and emitted on
 * [events], without content (INV-07).
 */
class CapturePipeline(
    private val config: CaptureConfig,
    private val clock: Clock,
    private val gate: suspend () -> CaptureGate,
    private val screens: ScreenSource,
    private val metas: ScreenMetaReader,
    private val privacy: PrivacyGate,
    private val extractor: TreeExtractor,
    private val screenshot: ScreenshotStep,
    private val merger: LineMerger,
    private val url: (packageName: String, root: UiNode, pageTitle: String?) -> String?,
    private val simHash: (String) -> Long,
    private val sink: CaptureSink,
    private val outcomes: suspend (PipelineEvent) -> Unit,
) : StableScreenHandler {
    private val mutableEvents = MutableSharedFlow<PipelineEvent>(extraBufferCapacity = EVENT_BUFFER)
    val events: SharedFlow<PipelineEvent> = mutableEvents.asSharedFlow()

    override suspend fun onStable(): PipelineEvent {
        val event = try {
            capture()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The class name only: the message may quote captured text (INV-07).
            SmLog.w(TAG, "capture_failed", e)
            PipelineEvent.Dropped(DropReason.ERROR)
        }
        try {
            outcomes(event)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SmLog.w(TAG, "outcome_failed", e)
        }
        mutableEvents.tryEmit(event)
        return event
    }

    private suspend fun capture(): PipelineEvent {
        // 1. Consent, pause, lock.
        val settings = gate()
        if (!settings.consented) return dropped(DropReason.CONSENT_MISSING)
        val pausedUntil = settings.pausedUntil
        if (pausedUntil != null && clock.nowMillis() < pausedUntil) return dropped(DropReason.PAUSED)
        if (screens.isDeviceLocked()) return dropped(DropReason.DEVICE_LOCKED)

        // 2. Root and window type.
        val window = when (val outcome = screens.activeWindow()) {
            is Outcome.Failure -> return dropped(outcome.error)
            is Outcome.Success -> outcome.value
        }
        try {
            return captureWindow(window)
        } finally {
            window.root.release()
        }
    }

    private suspend fun captureWindow(window: ActiveWindow): PipelineEvent {
        // 3. The package alone, before any content is read.
        val first = metas.read(window.packageName, window.windowId, window.title)
        (privacy.checkPackage(first) as? GateDecision.Deny)?.let { return dropped(it.reason) }

        // 4. The tree.
        val extraction = when (val outcome = extractor.extract(window.root)) {
            is Outcome.Failure -> return dropped(outcome.error)
            is Outcome.Success -> outcome.value
        }
        val meta = metas.completed(first, extraction)

        // 5. The tree's content.
        (privacy.checkTree(meta, extraction) as? GateDecision.Deny)?.let { return dropped(it.reason) }

        // 6. Secure window and OCR, one screenshot.
        val treeLines = extraction.lines.map { it.text }
        val treeSimHash = simHash(treeLines.joinToString("\n"))
        val ocrLines = when (val shot = screenshot.run(meta, extraction, treeSimHash)) {
            ScreenshotOutcome.Secure -> return dropped(DropReason.SECURE_WINDOW)
            is ScreenshotOutcome.Checked -> shot.ocrLines
        }

        // 7. Merge, URL, redaction.
        val merged = if (ocrLines.isNullOrEmpty()) {
            MergedLines(treeLines, TextSource.TREE)
        } else {
            merger.merge(extraction.lines, ocrLines)
        }
        val text = privacy.redact(merged.lines.joinToString("\n")).text
        val pageUrl = url(meta.packageName, window.root, extraction.pageTitle)

        // 8. Minimal length.
        if (text.trim().length < config.minTextChars) return dropped(DropReason.TOO_SHORT)

        // 9. Deduplication and storage.
        return when (val result = sink.ingest(RawCapture(meta, pageUrl, text, merged.textSource, treeSimHash))) {
            is IngestResult.Stored -> PipelineEvent.Stored(result.captureId)
            is IngestResult.Deduplicated -> PipelineEvent.Deduplicated(result.captureId)
        }
    }

    private fun dropped(reason: DropReason) = PipelineEvent.Dropped(reason)

    companion object {
        private const val TAG = "CapturePipeline"
        private const val EVENT_BUFFER = 16

        /** The `metric_daily` key of an outcome: `capture_stored`, `capture_deduplicated`, `capture_dropped_<reason>`. */
        fun metricKey(event: PipelineEvent): String = when (event) {
            is PipelineEvent.Stored -> "capture_stored"
            is PipelineEvent.Deduplicated -> "capture_deduplicated"
            is PipelineEvent.Dropped -> "capture_dropped_${event.reason.name.lowercase()}"
        }
    }
}
