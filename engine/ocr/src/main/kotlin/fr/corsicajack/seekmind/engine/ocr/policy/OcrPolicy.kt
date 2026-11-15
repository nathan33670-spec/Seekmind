package fr.corsicajack.seekmind.engine.ocr.policy

import fr.corsicajack.seekmind.core.common.Clock
import fr.corsicajack.seekmind.core.model.ScreenMeta
import fr.corsicajack.seekmind.core.model.TreeExtraction
import fr.corsicajack.seekmind.core.model.config.OcrConfig
import fr.corsicajack.seekmind.core.model.port.ThermalGovernor

/**
 * When to run the OCR (docs/specs/ocr.md §5): only if the screen needs it, has not been read already,
 * the governor allows it and the budget permits. A true answer counts against the budget and enters
 * the memo, whatever becomes of the screenshot: a screen whose OCR failed is not retried at once.
 * Thread-safe. Times are monotonic ([Clock.elapsedRealtime]).
 */
class OcrPolicy(private val config: OcrConfig, private val governor: ThermalGovernor, private val clock: Clock) {
    private val memo = object : LinkedHashMap<Pair<String, Long>, Unit>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String, Long>, Unit>) =
            size > config.ocrMemoSize
    }
    private val lastMinute = ArrayDeque<Long>()
    private val lastDay = ArrayDeque<Long>()
    private var last: Long? = null

    @Synchronized
    fun shouldOcr(meta: ScreenMeta, extraction: TreeExtraction, treeSimHash: Long): Boolean {
        if (!needsOcr(extraction)) return false
        val key = meta.packageName to treeSimHash
        if (memo.containsKey(key)) return false
        if (!governor.state.value.allowsOcr) return false
        val now = clock.elapsedRealtime()
        if (!withinBudget(now)) return false
        memo[key] = Unit
        last = now
        lastMinute.addLast(now)
        lastDay.addLast(now)
        return true
    }

    /** Little text, or text on a small part of a screen holding few characters (docs/specs/ocr.md §5). */
    fun needsOcr(extraction: TreeExtraction): Boolean = extraction.textChars < config.lowTextChars ||
        (extraction.textCoverage < config.lowCoverage && extraction.textChars < config.lowCoverageMaxChars)

    private fun withinBudget(now: Long): Boolean {
        while (lastMinute.isNotEmpty() && now - lastMinute.first() >= MINUTE_MS) lastMinute.removeFirst()
        while (lastDay.isNotEmpty() && now - lastDay.first() >= DAY_MS) lastDay.removeFirst()
        val previous = last
        return (previous == null || now - previous >= config.minIntervalMs) &&
            lastMinute.size < config.maxPerMinute &&
            lastDay.size < config.maxPerDay
    }

    private companion object {
        const val MINUTE_MS = 60_000L
        const val DAY_MS = 24 * 3_600_000L
    }
}
