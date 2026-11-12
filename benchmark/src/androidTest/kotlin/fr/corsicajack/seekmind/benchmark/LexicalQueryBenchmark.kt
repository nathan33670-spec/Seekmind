package fr.corsicajack.seekmind.benchmark

import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.corsicajack.seekmind.core.database.DatabaseHandle
import fr.corsicajack.seekmind.core.model.SearchFilters
import fr.corsicajack.seekmind.core.testing.SyntheticCorpus
import fr.corsicajack.seekmind.core.testing.SyntheticDatabase
import fr.corsicajack.seekmind.core.testing.TemporaryDatabase
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ENF-PERF-03: lexical query (docs/specs/stockage.md §5) on 100,000 synthetic captures, 50 results.
 * Target ≤ 100 ms (p95) for the first display. The database is generated once for the class.
 */
@RunWith(AndroidJUnit4::class)
class LexicalQueryBenchmark {
    @get:Rule
    val benchmark = BenchmarkRule()

    private val random = Random(11)

    @Test
    fun oneWord() = measure { phrase(corpus.queryWord(random)) }

    @Test
    fun twoWords() = measure { "${phrase(corpus.queryWord(random))} ${phrase(corpus.queryWord(random))}" }

    @Test
    fun prefix() = measure { phrase(corpus.queryWord(random).take(3)) + "*" }

    /**
     * A term as an FTS5 string, as engine:search will build it: "rendez-vous" would otherwise read as
     * FTS5 syntax ("no such column: vous").
     */
    private fun phrase(term: String) = "\"" + term.replace("\"", "\"\"") + "\""

    private fun measure(next: () -> String) {
        val search = handle.search
        benchmark.measureRepeated {
            val match = runWithTimingDisabled(next)
            runBlocking { search.lexical(match, SearchFilters(), LIMIT) }
        }
    }

    companion object {
        private const val COUNT = 100_000
        private const val LIMIT = 50
        private val corpus = SyntheticCorpus(seed = 3)
        private val database =
            TemporaryDatabase(InstrumentationRegistry.getInstrumentation().targetContext, "bench-lexical")
        private lateinit var handle: DatabaseHandle

        @BeforeClass
        @JvmStatic
        fun generate() = runBlocking<Unit> {
            database.delete()
            handle = database.open()
            SyntheticDatabase(handle, corpus).populate(COUNT)
            Unit
        }

        @AfterClass
        @JvmStatic
        fun clean() = database.delete()
    }
}
