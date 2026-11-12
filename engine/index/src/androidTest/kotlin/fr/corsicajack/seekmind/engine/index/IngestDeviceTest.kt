package fr.corsicajack.seekmind.engine.index

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.model.IngestResult
import fr.corsicajack.seekmind.core.model.SearchFilters
import fr.corsicajack.seekmind.core.testing.FakeClock
import fr.corsicajack.seekmind.core.testing.Models
import fr.corsicajack.seekmind.core.testing.SyntheticCorpus
import fr.corsicajack.seekmind.core.testing.TemporaryDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** Ingestion on the real encrypted database (ticket M4-02, docs/specs/indexation.md §6, ENF-PERF-05). */
@RunWith(AndroidJUnit4::class)
class IngestDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val database = TemporaryDatabase(context, "ingest_test")
    private val clock = FakeClock()
    private var requests = 0

    @After
    fun tearDown() = database.delete()

    @Test
    fun aStoredCaptureIsFoundByKeywordsWithItsFragmentsPending() = runBlocking<Unit> {
        val handle = database.open()
        val service = IngestService(handle.captures, { requests++ }, clock)
        val text = (1..30).joinToString("\n") {
            "Ligne $it : le phare de zanzibar éclaire la côte, ${"texte ".repeat(8)}"
        }

        val stored = service.ingest(Models.rawCapture(text)) as IngestResult.Stored
        val found = handle.search.lexical("\"zanzibar\"", SearchFilters(), 10)
        assertThat(found.map { it.captureId }).containsExactly(stored.captureId)
        val pending = handle.vectors.pendingChunks(10).filter { it.captureId == stored.captureId }
        assertThat(pending).hasSize(Chunker().split(TextNormalizer().normalize(text)).size)
        assertThat(requests).isEqualTo(1)

        clock.advance(60_000)
        assertThat(service.ingest(Models.rawCapture(text))).isEqualTo(IngestResult.Deduplicated(stored.captureId))
        assertThat(handle.captures.get(stored.captureId)?.lastSeenAt).isEqualTo(clock.now)
    }

    /** ENF-PERF-05: at most 15 ms per ingestion at the 95th percentile. */
    @Test
    fun ingestionStaysUnder15MsAtThe95thPercentile() = runBlocking<Unit> {
        val handle = database.open()
        val service = IngestService(handle.captures, { requests++ }, clock)
        val corpus = SyntheticCorpus()
        val durations = LongArray(COUNT)
        for (i in 0 until COUNT) {
            val capture = corpus.capture(i)
            val raw = Models.rawCapture(capture.text, Models.screenMeta(packageName = capture.packageName))
            clock.advance(30_000)
            val start = System.nanoTime()
            service.ingest(raw)
            durations[i] = System.nanoTime() - start
        }
        durations.sort()
        val p50 = durations[COUNT / 2] / 1e6
        val p95 = durations[COUNT * 95 / 100] / 1e6
        Log.i(TAG, "m4_02_ingest_p50_ms=$p50")
        Log.i(TAG, "m4_02_ingest_p95_ms=$p95")
        assertThat(p95).isAtMost(15.0)
    }

    private companion object {
        const val TAG = "SeekMindBench"
        const val COUNT = 1_000
    }
}
