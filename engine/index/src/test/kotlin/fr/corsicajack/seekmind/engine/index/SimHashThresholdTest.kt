package fr.corsicajack.seekmind.engine.index

import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.model.config.IndexConfig
import fr.corsicajack.seekmind.core.testing.SyntheticCorpus
import java.io.File
import kotlin.random.Random
import org.junit.Test

/**
 * Checks the duplicate threshold of docs/specs/indexation.md §3 (ticket M4-01) on four kinds of pairs
 * of synthetic screens, and writes the distribution of the distances to
 * `build/reports/simhash-threshold.txt` for the pull request.
 */
class SimHashThresholdTest {
    private val corpus = SyntheticCorpus()
    private val normalizer = TextNormalizer()
    private val threshold = IndexConfig().dedupMaxHamming
    private val pool: List<String> =
        (0 until 4_000).flatMap { corpus.capture(it).text.split('\n') }.filter { it.length > 20 }.distinct()

    @Test
    fun `distances by kind of pair`() {
        val random = Random(4_01)
        val identical = mutableListOf<Int>()
        val oneLine = mutableListOf<Int>()
        val scroll = mutableListOf<Int>()
        val unrelated = mutableListOf<Int>()
        val independent = mutableListOf<Int>()
        repeat(PAIRS) {
            val screen = lines(random, 12)
            identical += distance(screen, screen.toList())
            oneLine += distance(screen, screen.toMutableList().also { it[random.nextInt(it.size)] = pick(random) })
            // A list of 40 lines, 12 on screen, moved by one to three lines.
            val list = lines(random, 40)
            val top = random.nextInt(0, 25)
            val step = random.nextInt(1, 4)
            scroll += distance(list.subList(top, top + 12), list.subList(top + step, top + step + 12))
            unrelated += distance(screen, lines(random, 12))
            independent += distance(randomText(random), randomText(random))
        }
        val report = buildString {
            appendLine("SimHash: distances between pairs of synthetic screens of 12 lines ($PAIRS pairs per kind)")
            appendLine("threshold (dedupMaxHamming) = $threshold")
            for ((name, values) in listOf(
                "identical" to identical,
                "one line changed" to oneLine,
                "scroll by 1-3 lines" to scroll,
                "unrelated screens" to unrelated,
                "independent texts" to independent,
            )) {
                val sorted = values.sorted()
                val atMost = values.count { it <= threshold } * 100.0 / values.size
                appendLine(
                    "%-20s min %2d  p10 %2d  median %2d  p90 %2d  max %2d  mean %5.1f  <= threshold %5.1f %%".format(
                        name,
                        sorted.first(),
                        sorted[values.size / 10],
                        sorted[values.size / 2],
                        sorted[values.size * 9 / 10],
                        sorted.last(),
                        values.average(),
                        atMost,
                    ),
                )
            }
        }
        println(report)
        File("build/reports").mkdirs()
        File("build/reports/simhash-threshold.txt").writeText(report)

        assertThat(identical.toSet()).containsExactly(0)
        // Independent texts: half the bits differ on average.
        assertThat(independent.average()).isWithin(2.0).of(32.0)
        // Unrelated screens of the corpus share its templates, hence a lower mean: none is a duplicate.
        assertThat(unrelated.count { it <= threshold }).isEqualTo(0)
    }

    private fun lines(random: Random, count: Int) = List(count) { pick(random) }

    private fun pick(random: Random) = pool[random.nextInt(pool.size)]

    /** 12 lines of 8 pseudo-words of two or three syllables: texts that share nothing. */
    private fun randomText(random: Random) = List(12) {
        List(8) { List(random.nextInt(2, 4)) { SYLLABLES[random.nextInt(SYLLABLES.size)] }.joinToString("") }
            .joinToString(" ")
    }

    private fun distance(a: List<String>, b: List<String>): Int = SimHash.hamming(
        SimHash.of(normalizer.normalize(a.joinToString("\n"))),
        SimHash.of(normalizer.normalize(b.joinToString("\n"))),
    )

    private companion object {
        const val PAIRS = 2_000
        val SYLLABLES = listOf(
            "ba", "be", "bi", "bo", "bu", "ca", "ce", "ci", "co", "da", "de", "di", "do", "fa", "fe", "fi", "ga",
            "go", "la", "le", "li", "lo", "lu", "ma", "me", "mi", "mo", "na", "ne", "ni", "no", "pa", "pe", "pi",
            "po", "ra", "re", "ri", "ro", "sa", "se", "si", "so", "ta", "te", "ti", "to", "va", "ve", "vi", "vo",
            "tra", "pré", "con", "ment", "tion", "eau", "ou", "an", "on",
        )
    }
}
