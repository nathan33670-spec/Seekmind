package fr.corsicajack.seekmind.engine.capture.pipeline

import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.common.IntRect
import fr.corsicajack.seekmind.core.common.Outcome
import fr.corsicajack.seekmind.core.common.SmLog
import fr.corsicajack.seekmind.core.model.DropReason
import fr.corsicajack.seekmind.core.model.GateDecision
import fr.corsicajack.seekmind.core.model.IngestResult
import fr.corsicajack.seekmind.core.model.OcrLine
import fr.corsicajack.seekmind.core.model.PipelineEvent
import fr.corsicajack.seekmind.core.model.RawCapture
import fr.corsicajack.seekmind.core.model.Redaction
import fr.corsicajack.seekmind.core.model.ScreenMeta
import fr.corsicajack.seekmind.core.model.TextSource
import fr.corsicajack.seekmind.core.model.TreeExtraction
import fr.corsicajack.seekmind.core.model.config.CaptureConfig
import fr.corsicajack.seekmind.core.model.port.CaptureSink
import fr.corsicajack.seekmind.core.model.port.PrivacyGate
import fr.corsicajack.seekmind.core.testing.FakeClock
import fr.corsicajack.seekmind.engine.capture.meta.ActivityTracker
import fr.corsicajack.seekmind.engine.capture.meta.ScreenMetaReader
import fr.corsicajack.seekmind.engine.capture.tree.ActiveWindow
import fr.corsicajack.seekmind.engine.capture.tree.FakeTree
import fr.corsicajack.seekmind.engine.capture.tree.TreeExtractor
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * The steps of docs/specs/capture.md §6 with doubles: each step drops with its reason, in the order of
 * the spec (nothing of the screen is read before the package is accepted), and an exception becomes
 * `Dropped(ERROR)` without reaching the caller.
 */
class CapturePipelineTest {
    private val clock = FakeClock()
    private val config = CaptureConfig()
    private val tree = FakeTree()

    private var gate = CaptureGate(consented = true, pausedUntil = null)
    private var locked = false
    private var window: Outcome<ActiveWindow, DropReason>? = null
    private var windowsAsked = 0
    private var packageDecision: GateDecision = GateDecision.Allow
    private var treeDecision: GateDecision = GateDecision.Allow
    private var screenshot: ScreenshotOutcome = ScreenshotOutcome.Checked(null)
    private var treeText = "A screen with enough text to be stored"
    private var ingestFailure: Exception? = null
    private var ingestResult: IngestResult = IngestResult.Stored(42)

    private val calls = mutableListOf<String>()
    private val stored = mutableListOf<RawCapture>()
    private val outcomes = mutableListOf<PipelineEvent>()

    private val screens = object : ScreenSource {
        override fun isDeviceLocked(): Boolean = locked

        override fun activeWindow(): Outcome<ActiveWindow, DropReason> {
            windowsAsked++
            return window ?: Outcome.Success(
                ActiveWindow(
                    tree.tree {
                        className = "android.widget.FrameLayout"
                        bounds = IntRect(0, 0, 1_080, 2_400)
                        child { text = treeText }
                    },
                    PACKAGE,
                    windowId = 7,
                    title = "Notes",
                ),
            )
        }
    }

    private val privacy = object : PrivacyGate {
        override fun checkPackage(meta: ScreenMeta): GateDecision {
            calls += "checkPackage"
            return packageDecision
        }

        override fun checkTree(meta: ScreenMeta, extraction: TreeExtraction): GateDecision {
            calls += "checkTree"
            return treeDecision
        }

        override fun redact(text: String): Redaction {
            calls += "redact"
            return Redaction(text.replace("4111", "[•••]"), maskedCount = 0)
        }
    }

    private var ingestMs = 0L

    private val sink = object : CaptureSink {
        override suspend fun ingest(raw: RawCapture): IngestResult {
            ingestFailure?.let { throw it }
            clock.advance(ingestMs)
            stored += raw
            return ingestResult
        }
    }

    private val extractor = object {
        val real = TreeExtractor(config, clock)
    }

    private fun pipeline() = CapturePipeline(
        config = config,
        clock = clock,
        gate = { gate },
        screens = screens,
        metas = ScreenMetaReader(ActivityTracker({ _, _ -> true }), clock),
        privacy = privacy,
        extractor = extractor.real,
        screenshot = { _, _, _ ->
            calls += "screenshot"
            screenshot
        },
        merger = LineMerger.Append,
        url = { _, _, _ -> null },
        simHash = { it.length.toLong() },
        sink = sink,
    ) { outcomes += it }

    private fun run(): PipelineEvent = runBlocking { pipeline().onStable() }

    private fun dropped(reason: DropReason) = PipelineEvent.Dropped(reason)

    @Test
    fun `an accepted screen is stored, redacted, with its tree fingerprint`() {
        treeText = "Card 4111 and enough text to be stored"
        assertThat(run()).isEqualTo(PipelineEvent.Stored(42))
        val raw = stored.single()
        assertThat(raw.text).isEqualTo("Card [•••] and enough text to be stored")
        assertThat(raw.textSource).isEqualTo(TextSource.TREE)
        assertThat(raw.treeSimHash).isEqualTo(treeText.length.toLong())
        assertThat(raw.meta.packageName).isEqualTo(PACKAGE)
        assertThat(calls).containsExactly("checkPackage", "checkTree", "screenshot", "redact").inOrder()
        assertThat(tree.unreleased).isEmpty()
    }

    @Test
    fun `a deduplicated screen is reported as such`() {
        ingestResult = IngestResult.Deduplicated(41)
        assertThat(run()).isEqualTo(PipelineEvent.Deduplicated(41))
    }

    @Test
    fun `without consent nothing is read`() {
        gate = CaptureGate(consented = false, pausedUntil = null)
        assertThat(run()).isEqualTo(dropped(DropReason.CONSENT_MISSING))
        assertThat(windowsAsked).isEqualTo(0)
        assertThat(calls).isEmpty()
    }

    @Test
    fun `a pause stops the capture until its end`() {
        gate = CaptureGate(consented = true, pausedUntil = clock.now + 1)
        assertThat(run()).isEqualTo(dropped(DropReason.PAUSED))
        assertThat(windowsAsked).isEqualTo(0)
        clock.advance(1)
        assertThat(run()).isEqualTo(PipelineEvent.Stored(42))
    }

    @Test
    fun `a locked device is not read`() {
        locked = true
        assertThat(run()).isEqualTo(dropped(DropReason.DEVICE_LOCKED))
        assertThat(windowsAsked).isEqualTo(0)
    }

    @Test
    fun `no root and another kind of window`() {
        window = Outcome.Failure(DropReason.NO_ROOT)
        assertThat(run()).isEqualTo(dropped(DropReason.NO_ROOT))
        window = Outcome.Failure(DropReason.NOT_APP_WINDOW)
        assertThat(run()).isEqualTo(dropped(DropReason.NOT_APP_WINDOW))
        assertThat(calls).isEmpty()
    }

    @Test
    fun `a refused package is not read`() {
        for (reason in listOf(
            DropReason.APP_IGNORED,
            DropReason.APP_EXCLUDED,
            DropReason.APP_FAMILY_DISABLED,
            DropReason.APP_SENSITIVE,
            DropReason.APP_CATEGORY,
        )) {
            calls.clear()
            packageDecision = GateDecision.Deny(reason)
            val before = tree.obtained.size
            assertThat(run()).isEqualTo(dropped(reason))
            // Only the decision on the package: no tree read (no child obtained), no screenshot.
            assertThat(calls).containsExactly("checkPackage")
            assertThat(tree.obtained.size - before).isEqualTo(1)
        }
        assertThat(stored).isEmpty()
        assertThat(tree.unreleased).isEmpty()
    }

    @Test
    fun `a refused tree stops before the screenshot`() {
        for (reason in listOf(DropReason.PASSWORD_FIELD, DropReason.PRIVATE_BROWSING)) {
            calls.clear()
            treeDecision = GateDecision.Deny(reason)
            assertThat(run()).isEqualTo(dropped(reason))
            assertThat(calls).containsExactly("checkPackage", "checkTree").inOrder()
        }
        assertThat(stored).isEmpty()
    }

    @Test
    fun `a secure window is not stored`() {
        screenshot = ScreenshotOutcome.Secure
        assertThat(run()).isEqualTo(dropped(DropReason.SECURE_WINDOW))
        assertThat(calls).doesNotContain("redact")
        assertThat(stored).isEmpty()
    }

    @Test
    fun `the lines of the OCR are merged and marked`() {
        screenshot = ScreenshotOutcome.Checked(listOf(OcrLine("Text in an image", IntRect(0, 0, 10, 10), 0.9f)))
        assertThat(run()).isEqualTo(PipelineEvent.Stored(42))
        assertThat(stored.single().text).endsWith("\nText in an image")
        assertThat(stored.single().textSource).isEqualTo(TextSource.TREE or TextSource.OCR)
    }

    @Test
    fun `a short text is not stored`() {
        treeText = "Too short"
        assertThat(run()).isEqualTo(dropped(DropReason.TOO_SHORT))
        assertThat(stored).isEmpty()
    }

    @Test
    fun `an exception in a step is an error, never thrown`() {
        ingestFailure = IllegalStateException("database unavailable")
        assertThat(run()).isEqualTo(dropped(DropReason.ERROR))
        assertThat(tree.unreleased).isEmpty()
    }

    @Test
    fun `every outcome is counted and emitted`() {
        run()
        gate = CaptureGate(consented = false, pausedUntil = null)
        run()
        assertThat(outcomes).containsExactly(PipelineEvent.Stored(42), dropped(DropReason.CONSENT_MISSING)).inOrder()
        assertThat(outcomes.map(CapturePipeline::metricKey))
            .containsExactly("capture_stored", "capture_dropped_consent_missing").inOrder()
    }

    @Test
    fun `a failure of the counters does not change the outcome`() {
        val failing = CapturePipeline(
            config, clock, { gate }, screens, ScreenMetaReader(ActivityTracker({ _, _ -> true }), clock), privacy,
            extractor.real, ScreenshotStep.NotProbed, LineMerger.Append, { _, _, _ -> null }, { 0L }, sink,
        ) { error("metrics unavailable") }
        assertThat(runBlocking { failing.onStable() }).isEqualTo(PipelineEvent.Stored(42))
    }

    @Test
    fun `a slow step is logged by number and duration, without content`() {
        val written = mutableListOf<String>()
        val previous = SmLog.sink
        SmLog.sink = SmLog.Sink { _, _, message -> written += message }
        try {
            treeText = "Card 4111 and enough text to be stored"
            ingestMs = 3_000
            assertThat(run()).isEqualTo(PipelineEvent.Stored(42))
        } finally {
            SmLog.sink = previous
        }
        assertThat(written).containsExactly("capture_step_slow step=9 ms=3000")
    }

    @Test
    fun `a capture still running after the stall delay is logged from another thread, by step`() {
        val written = java.util.concurrent.CopyOnWriteArrayList<String>()
        val logged = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val previous = SmLog.sink
        SmLog.sink = SmLog.Sink { _, _, message ->
            written += message
            if (message.startsWith("capture_stalled")) logged.countDown()
        }
        val blocking = object : CaptureSink {
            override suspend fun ingest(raw: RawCapture): IngestResult {
                // The capture thread is held until the watchdog has spoken.
                release.await(5, java.util.concurrent.TimeUnit.SECONDS)
                return ingestResult
            }
        }
        try {
            treeText = "Card 4111 and enough text to be stored"
            val stalling = CapturePipeline(
                config, clock, { gate }, screens, ScreenMetaReader(ActivityTracker({ _, _ -> true }), clock), privacy,
                extractor.real, ScreenshotStep.NotProbed, LineMerger.Append, { _, _, _ -> null }, { 0L }, blocking,
                stallMs = 50,
            ) { }
            val thread = Thread { runBlocking { stalling.onStable() } }.apply { start() }
            assertThat(logged.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue()
            release.countDown()
            thread.join(5_000)
        } finally {
            SmLog.sink = previous
        }
        assertThat(written.first { it.startsWith("capture_stalled") }).matches("capture_stalled step=9 ms=\\d+")
    }

    private companion object {
        const val PACKAGE = "org.example.notes"
    }
}
