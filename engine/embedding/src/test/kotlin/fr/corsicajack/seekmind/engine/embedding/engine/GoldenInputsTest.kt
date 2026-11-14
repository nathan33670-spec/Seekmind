package fr.corsicajack.seekmind.engine.embedding.engine

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import fr.corsicajack.seekmind.core.model.config.EmbeddingConfig
import fr.corsicajack.seekmind.engine.embedding.tokenizer.ModelVocabularies
import fr.corsicajack.seekmind.engine.embedding.tokenizer.UnigramTokenizer
import java.io.File
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The engine gives the model exactly the token ids of `testdata/embedding_golden_<id>.json`: prefixes,
 * tokenizer and truncation as in the reference (docs/specs/embeddings.md §1, §4).
 */
class GoldenInputsTest {
    @Test
    fun frInputsMatchTheReference() = check("fr")

    @Test
    fun multiInputsMatchTheReference() = check("multi")

    private fun check(model: String) = runTest {
        val vocabulary = ModelVocabularies.require(model)
        val items = golden(model)
        assertThat(items).hasSize(50)
        val encoder = IdsEncoder()
        val engine = OrtEmbeddingEngine(
            BuiltInModels.FR,
            UnigramTokenizer(vocabulary),
            encoder,
            EmbeddingConfig(),
            StandardTestDispatcher(testScheduler),
        )
        for ((kind, text, ids) in items) {
            if (kind == "query") engine.embedQuery(text) else engine.embedPassages(listOf(text))
            assertWithMessage("$kind of ${text.length} chars").that(
                encoder.last.map {
                    it.toInt()
                },
            ).isEqualTo(ids.toList())
        }
        assertThat(items.map { it.first }.toSet()).containsExactly("query", "passage")
        assertThat(items.maxOf { it.third.size }).isAtMost(EmbeddingConfig().maxPassageTokens)
    }

    /** kind, text, inputIds; the golden files hold no escaped character. */
    private fun golden(model: String): List<Triple<String, String, IntArray>> {
        val testdata = File(System.getProperty("seekmind.testdata") ?: "../../testdata")
        val text = File(testdata, "embedding_golden_$model.json").readText()
        check('\\' !in text) { "escaped characters: extend the reader" }
        return Regex("""\{"kind":"(query|passage)","text":"([^"]*)","inputIds":\[([0-9,]*)]""").findAll(text).map {
            Triple(it.groupValues[1], it.groupValues[2], it.groupValues[3].split(',').map(String::toInt).toIntArray())
        }.toList()
    }

    private class IdsEncoder : Encoder {
        var last = LongArray(0)
        override val dimensions = 4

        override fun encode(batch: PaddedBatch): FloatArray {
            last = batch.inputIds.copyOf()
            return FloatArray(batch.rows * batch.length * dimensions) { 1f }
        }

        override fun close() = Unit
    }
}
