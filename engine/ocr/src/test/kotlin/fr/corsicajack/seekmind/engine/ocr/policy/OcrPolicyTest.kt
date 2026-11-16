package fr.corsicajack.seekmind.engine.ocr.policy

import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.common.IntRect
import fr.corsicajack.seekmind.core.model.TreeExtraction
import fr.corsicajack.seekmind.core.model.config.OcrConfig
import fr.corsicajack.seekmind.core.model.port.GovernorState
import fr.corsicajack.seekmind.core.model.port.ThermalGovernor
import fr.corsicajack.seekmind.core.model.port.ThrottleReason
import fr.corsicajack.seekmind.core.testing.FakeClock
import fr.corsicajack.seekmind.core.testing.Models
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test

/** docs/specs/ocr.md §5. */
class OcrPolicyTest {
    private val clock = FakeClock()
    private val allowed = GovernorState(allowsOcr = true, allowsEmbedding = true, embeddingThreads = 2, reason = null)
    private val governor = object : ThermalGovernor {
        override val state = MutableStateFlow(allowed)
    }
    private val config = OcrConfig()
    private val policy = OcrPolicy(config, governor, clock)
    private val meta = Models.screenMeta()

    private fun extraction(chars: Int, coverage: Float) = TreeExtraction(
        lines = emptyList(),
        headings = emptyList(),
        hasPasswordField = false,
        viewIds = emptySet(),
        nodeCount = 10,
        truncated = false,
        textChars = chars,
        textCoverage = coverage,
        windowBounds = IntRect(0, 0, 1080, 2400),
        durationMs = 5,
    )

    private val needy = extraction(chars = 40, coverage = 0.5f)

    @Test
    fun theDecisionTableOfTheNeed() {
        // textChars < 80, or (coverage < 0.15 and textChars < 600).
        assertThat(policy.needsOcr(extraction(79, 0.9f))).isTrue()
        assertThat(policy.needsOcr(extraction(80, 0.9f))).isFalse()
        assertThat(policy.needsOcr(extraction(599, 0.14f))).isTrue()
        assertThat(policy.needsOcr(extraction(600, 0.14f))).isFalse()
        assertThat(policy.needsOcr(extraction(300, 0.15f))).isFalse()
    }

    @Test
    fun aScreenRichInTextIsNotRead() {
        assertThat(policy.shouldOcr(meta, extraction(2_000, 0.6f), treeSimHash = 1)).isFalse()
    }

    @Test
    fun aScreenAlreadyReadIsNotReadAgainButAnotherIs() {
        assertThat(policy.shouldOcr(meta, needy, treeSimHash = 1)).isTrue()
        clock.advance(config.minIntervalMs)
        assertThat(policy.shouldOcr(meta, needy, treeSimHash = 1)).isFalse()
        assertThat(policy.shouldOcr(meta, needy, treeSimHash = 2)).isTrue()
        clock.advance(config.minIntervalMs)
        assertThat(policy.shouldOcr(meta.copy(packageName = "com.example.other"), needy, treeSimHash = 1)).isTrue()
    }

    @Test
    fun theMemoForgetsTheOldestScreen() {
        repeat(config.ocrMemoSize + 1) { i ->
            assertThat(policy.shouldOcr(meta, needy, treeSimHash = i.toLong())).isTrue()
            clock.advance(10 * 60_000L) // out of every budget window but the day
        }
        // Screen 0 left the memo of 20 screens; screen 20 is still in it.
        assertThat(policy.shouldOcr(meta, needy, treeSimHash = 0)).isTrue()
        clock.advance(10 * 60_000L)
        assertThat(policy.shouldOcr(meta, needy, treeSimHash = config.ocrMemoSize.toLong())).isFalse()
    }

    @Test
    fun theGovernorCanRefuse() {
        governor.state.value = allowed.copy(allowsOcr = false, allowsEmbedding = false, reason = ThrottleReason.THERMAL)
        assertThat(policy.shouldOcr(meta, needy, treeSimHash = 1)).isFalse()
        governor.state.value = allowed
        assertThat(policy.shouldOcr(meta, needy, treeSimHash = 1)).isTrue()
    }

    @Test
    fun fiveSecondsBetweenTwoOcrs() {
        assertThat(policy.shouldOcr(meta, needy, treeSimHash = 1)).isTrue()
        clock.advance(config.minIntervalMs - 1)
        assertThat(policy.shouldOcr(meta, needy, treeSimHash = 2)).isFalse()
        clock.advance(1)
        assertThat(policy.shouldOcr(meta, needy, treeSimHash = 2)).isTrue()
    }

    @Test
    fun sixPerMinuteAtMost() {
        // One every 5 s would allow 12 a minute: the minute budget caps them at 6.
        var granted = 0
        repeat(12) { i ->
            if (policy.shouldOcr(meta, needy, treeSimHash = i.toLong())) granted++
            clock.advance(config.minIntervalMs)
        }
        assertThat(granted).isEqualTo(config.maxPerMinute)
        assertThat(policy.shouldOcr(meta, needy, treeSimHash = 100)).isTrue() // a minute after the first
    }

    @Test
    fun fourHundredPerDayAtMost() {
        val config = OcrConfig(maxPerMinute = 1_000, ocrMemoSize = 1)
        val policy = OcrPolicy(config, governor, clock)
        var granted = 0
        repeat(config.maxPerDay + 10) { i ->
            if (policy.shouldOcr(meta, needy, treeSimHash = i.toLong())) granted++
            clock.advance(config.minIntervalMs)
        }
        assertThat(granted).isEqualTo(config.maxPerDay)
        clock.advance(24 * 3_600_000L)
        assertThat(policy.shouldOcr(meta, needy, treeSimHash = 10_000)).isTrue()
    }
}
