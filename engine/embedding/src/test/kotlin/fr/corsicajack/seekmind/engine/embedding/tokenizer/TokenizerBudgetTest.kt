package fr.corsicajack.seekmind.engine.embedding.tokenizer

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Time and memory budgets of ticket M4-03 (docs/specs/embeddings.md §3.2). */
class TokenizerBudgetTest {
    @Test
    fun `an 800-character passage is tokenized in less than 5 ms`() {
        val tokenizer = UnigramTokenizer(ModelVocabularies.require("fr"))
        val passage = passage(PASSAGE_CHARS)
        assertThat(passage.length).isEqualTo(PASSAGE_CHARS)
        repeat(WARM_UP) { tokenizer.encode(passage, Int.MAX_VALUE) }
        val timesNs = LongArray(RUNS) {
            val start = System.nanoTime()
            tokenizer.encode(passage, Int.MAX_VALUE)
            System.nanoTime() - start
        }.sorted()
        val median = timesNs[RUNS / 2]
        println(
            "800 characters, ${tokenizer.encode(
                passage,
                Int.MAX_VALUE,
            ).size} ids: median ${median / 1_000} µs, max ${timesNs.last() / 1_000} µs",
        )
        assertThat(median).isLessThan(BUDGET_NS)
    }

    @Test
    fun `the largest vocabulary stays within 16 MB`() {
        for (id in listOf("fr", "multi")) {
            val file = ModelVocabularies.file(id)
            val vocabulary = ModelVocabularies.require(id)
            val runtime = Runtime.getRuntime()
            System.gc()
            val before = runtime.totalMemory() - runtime.freeMemory()
            val loaded = Vocabulary.parse(file!!.readBytes())
            System.gc()
            val heap = runtime.totalMemory() - runtime.freeMemory() - before
            println(
                "$id: ${vocabulary.size} tokens, structures ${loaded.retainedBytes / 1024} KiB, " +
                    "heap growth after load ${heap / 1024} KiB",
            )
            assertThat(loaded.retainedBytes).isLessThan(BUDGET_BYTES)
        }
    }

    private fun passage(length: Int): String {
        val sentences = GoldenLines.read(ModelVocabularies.golden("fr")).map { it.text }
        val out = StringBuilder()
        var i = 0
        while (out.length < length) out.append(sentences[i++ % sentences.size]).append('\n')
        return out.substring(0, length)
    }

    private companion object {
        const val PASSAGE_CHARS = 800
        const val WARM_UP = 200
        const val RUNS = 201
        const val BUDGET_NS = 5_000_000L
        const val BUDGET_BYTES = 16L * 1024 * 1024
    }
}
