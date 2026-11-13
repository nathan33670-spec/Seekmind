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

    /**
     * ENF-PERF-05: at most 15 ms per ingestion at the 95th percentile, in steady state. As a microbenchmark
     * does, [WARMUP] ingestions are run first and not measured: the test APK is debuggable, its code is
     * interpreted until the JIT has compiled it. The in-memory part (normalisation, SimHash, chunking) is
     * measured apart, so that the log tells where the time goes.
     */
    @Test
    fun ingestionStaysUnder15MsAtThe95thPercentile() = runBlocking<Unit> {
        val handle = database.open()
        val service = IngestService(handle.captures, { requests++ }, clock)
        val corpus = SyntheticCorpus()
        val normalizer = TextNormalizer()
        val chunker = Chunker()
        repeat(WARMUP) { i -> service.ingest(raw(corpus, COUNT + i)) }

        val durations = LongArray(COUNT)
        val inMemory = LongArray(COUNT)
        val gcBefore = gcCount()
        for (i in 0 until COUNT) {
            val raw = raw(corpus, i)
            val before = System.nanoTime()
            normalizer.normalize(raw.text).let { SimHash.of(it) + chunker.split(it).size }
            inMemory[i] = System.nanoTime() - before
            val start = System.nanoTime()
            service.ingest(raw)
            durations[i] = System.nanoTime() - start
        }
        val gcs = gcCount() - gcBefore

        // The bare store insertion, as the core:database benchmark measures it (M1-07): locates the time.
        val storeOnly = LongArray(COUNT)
        for (i in 0 until COUNT) {
            val capture = corpus.capture(COUNT + WARMUP + i)
            val chunks = Chunker().split(capture.text).mapIndexed { n, span ->
                fr.corsicajack.seekmind.core.database.store.NewChunk(n, span.start, span.end)
            }
            val start = System.nanoTime()
            handle.captures.insert(capture, chunks)
            storeOnly[i] = System.nanoTime() - start
        }
        val p95 = percentile(durations, 95)
        Log.i(TAG, "m4_02_ingest_p50_ms=${percentile(durations, 50)}")
        Log.i(TAG, "m4_02_ingest_p95_ms=$p95")
        Log.i(TAG, "m4_02_ingest_p99_ms=${percentile(durations, 99)}")
        Log.i(TAG, "m4_02_in_memory_p95_ms=${percentile(inMemory, 95)}")
        Log.i(TAG, "m4_02_store_insert_p50_ms=${percentile(storeOnly, 50)}")
        Log.i(TAG, "m4_02_store_insert_p95_ms=${percentile(storeOnly, 95)}")
        Log.i(TAG, "m4_02_gc_during_ingest=$gcs")
        assertThat(p95).isAtMost(15.0)
    }

    private fun raw(corpus: SyntheticCorpus, index: Int) = corpus.capture(index).let {
        clock.advance(30_000)
        Models.rawCapture(it.text, Models.screenMeta(packageName = it.packageName))
    }

    private fun gcCount(): Long = android.os.Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull() ?: -1

    private fun percentile(values: LongArray, p: Int): Double = values.sortedArray()[values.size * p / 100] / 1e6

    private companion object {
        const val TAG = "SeekMindBench"
        const val COUNT = 1_000
        const val WARMUP = 300
    }
}
