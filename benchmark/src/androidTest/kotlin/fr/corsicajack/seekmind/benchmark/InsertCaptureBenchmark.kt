package fr.corsicajack.seekmind.benchmark

import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.corsicajack.seekmind.core.database.store.NewChunk
import fr.corsicajack.seekmind.core.testing.SyntheticCorpus
import fr.corsicajack.seekmind.core.testing.SyntheticDatabase
import fr.corsicajack.seekmind.core.testing.TemporaryDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ENF-PERF-05: insertion of one capture, complete transaction (capture, FTS5 trigger, chunks), on a
 * database already holding 10,000 captures. Target ≤ 15 ms (p95).
 */
@RunWith(AndroidJUnit4::class)
class InsertCaptureBenchmark {
    @get:Rule
    val benchmark = BenchmarkRule()

    private val database = TemporaryDatabase(InstrumentationRegistry.getInstrumentation().targetContext, "bench-insert")
    private val corpus = SyntheticCorpus(seed = 7)

    @Before
    fun fill() = runBlocking<Unit> {
        database.delete()
        SyntheticDatabase(database.open(), corpus).populate(BASELINE)
    }

    @After
    fun clean() = database.delete()

    @Test
    fun insertOneCapture() {
        val captures = database.open().captures
        var index = BASELINE
        benchmark.measureRepeated {
            val capture = corpus.capture(index++)
            runBlocking { captures.insert(capture, listOf(NewChunk(0, 0, minOf(capture.text.length, 800)))) }
        }
    }

    private companion object {
        const val BASELINE = 10_000
    }
}
