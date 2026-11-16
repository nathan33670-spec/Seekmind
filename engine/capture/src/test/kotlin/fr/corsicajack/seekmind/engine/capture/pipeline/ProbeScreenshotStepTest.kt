package fr.corsicajack.seekmind.engine.capture.pipeline

import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.common.IntRect
import fr.corsicajack.seekmind.core.model.OcrLine
import fr.corsicajack.seekmind.core.model.OcrOutcome
import fr.corsicajack.seekmind.core.model.ProbeResult
import fr.corsicajack.seekmind.core.model.ScreenMeta
import fr.corsicajack.seekmind.core.model.TreeExtraction
import fr.corsicajack.seekmind.core.model.config.OcrConfig
import fr.corsicajack.seekmind.core.model.port.GovernorState
import fr.corsicajack.seekmind.core.model.port.SecureWindowProbe
import fr.corsicajack.seekmind.core.model.port.ThermalGovernor
import fr.corsicajack.seekmind.core.model.port.ThrottleReason
import fr.corsicajack.seekmind.core.testing.FakeClock
import fr.corsicajack.seekmind.engine.ocr.policy.OcrPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** Step 6 (capture §6, ocr §3, §5): the policy decides the image, one probe serves both. */
class ProbeScreenshotStepTest {
    private val clock = FakeClock()
    private val meta = ScreenMeta("org.example.notes", null, null, windowId = 3, capturedAt = clock.now)
    private val line = OcrLine("Text in an image", IntRect(0, 0, 10, 10), 0.9f)

    private val governor = object : ThermalGovernor {
        override val state = MutableStateFlow(GovernorState(true, true, 2, null))
    }

    private var answer: ProbeResult = ProbeResult.NotSecure(null)
    private var rejected = false
    private val asked = mutableListOf<Boolean>()
    private val probe = object : SecureWindowProbe {
        override suspend fun probe(meta: ScreenMeta, windowBounds: IntRect, needImage: Boolean): ProbeResult {
            asked += needImage
            return answer
        }

        override fun rejects(meta: ScreenMeta, result: ProbeResult) = rejected
    }

    private fun extraction(chars: Int) = TreeExtraction(
        lines = emptyList(),
        headings = emptyList(),
        hasPasswordField = false,
        viewIds = emptySet(),
        nodeCount = 10,
        truncated = false,
        textChars = chars,
        textCoverage = 0.5f,
        windowBounds = IntRect(0, 0, 1080, 2400),
        durationMs = 5,
    )

    private fun run(chars: Int) = runBlocking {
        ProbeScreenshotStep(probe, OcrPolicy(OcrConfig(), governor, clock)).run(meta, extraction(chars), 7L)
    }

    @Test
    fun `little text asks the probe for the image, and its lines come back`() {
        answer = ProbeResult.NotSecure(OcrOutcome(listOf(line), durationMs = 30))
        assertThat(run(chars = 10)).isEqualTo(ScreenshotOutcome.Checked(listOf(line)))
        assertThat(asked).containsExactly(true)
    }

    @Test
    fun `enough text probes without the image`() {
        assertThat(run(chars = 2_000)).isEqualTo(ScreenshotOutcome.Checked(null))
        assertThat(asked).containsExactly(false)
    }

    @Test
    fun `a suspended governor runs no OCR, the tree is still captured`() {
        governor.state.value = GovernorState(false, false, 2, ThrottleReason.THERMAL)
        assertThat(run(chars = 10)).isEqualTo(ScreenshotOutcome.Checked(null))
        assertThat(asked).containsExactly(false)
    }

    @Test
    fun `what the probe rejects is a secure window`() {
        answer = ProbeResult.Secure
        rejected = true
        assertThat(run(chars = 10)).isEqualTo(ScreenshotOutcome.Secure)
    }

    @Test
    fun `an unknown probe that is not rejected goes on without OCR`() {
        answer = ProbeResult.Unknown
        assertThat(run(chars = 10)).isEqualTo(ScreenshotOutcome.Checked(null))
        answer = ProbeResult.Skipped
        assertThat(run(chars = 2_000)).isEqualTo(ScreenshotOutcome.Checked(null))
    }
}
