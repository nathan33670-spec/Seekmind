package fr.corsicajack.seekmind.engine.index

import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.database.store.CaptureStore
import fr.corsicajack.seekmind.core.database.store.HashRef
import fr.corsicajack.seekmind.core.database.store.NewCapture
import fr.corsicajack.seekmind.core.database.store.NewChunk
import fr.corsicajack.seekmind.core.model.IngestResult
import fr.corsicajack.seekmind.core.model.config.IndexConfig
import fr.corsicajack.seekmind.core.testing.FakeClock
import fr.corsicajack.seekmind.core.testing.Models
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** docs/specs/indexation.md §4 and §6 with a fake store (ticket M4-02). */
class IngestServiceTest {
    private val clock = FakeClock()
    private val store = FakeCaptureStore()
    private var requests = 0
    private val service = IngestService(store, { requests++ }, clock)
    private val screen = (1..12).joinToString("\n") { "Ligne $it de la conversation avec Camille sur le projet" }

    @Test
    fun `a new capture is stored with its pending fragments, and vectors are requested`() = runBlocking<Unit> {
        val result = service.ingest(Models.rawCapture("  $screen\n\n"))
        assertThat(result).isEqualTo(IngestResult.Stored(1))
        val (capture, chunks) = store.inserted.single()
        assertThat(capture.text).isEqualTo(screen) // normalised
        assertThat(capture.simhash).isEqualTo(SimHash.of(screen))
        assertThat(capture.firstSeenAt).isEqualTo(clock.now)
        assertThat(chunks).isNotEmpty()
        assertThat(chunks.map { it.ordinal }).isEqualTo(chunks.indices.toList())
        assertThat(requests).isEqualTo(1)
    }

    @Test
    fun `the same screen again is a duplicate, touched without a new row`() = runBlocking<Unit> {
        service.ingest(Models.rawCapture(screen))
        clock.advance(60_000)
        val again = service.ingest(Models.rawCapture(screen.replace("Ligne 3 ", "Ligne trois ")))
        assertThat(again).isEqualTo(IngestResult.Deduplicated(1))
        assertThat(store.inserted).hasSize(1)
        assertThat(store.touched).containsExactly(1L to clock.now)
    }

    // The four conditions of §4: each one is necessary.

    @Test
    fun `another package is not a duplicate`() = runBlocking<Unit> {
        service.ingest(Models.rawCapture(screen))
        val other = service.ingest(Models.rawCapture(screen, Models.screenMeta(packageName = "com.example.other")))
        assertThat(other).isInstanceOf(IngestResult.Stored::class.java)
    }

    @Test
    fun `a distant fingerprint is not a duplicate`() = runBlocking<Unit> {
        service.ingest(Models.rawCapture(screen))
        val unrelated = (1..12).joinToString("\n") { "Facture $it de l'électricité du mois de mars, à régler" }
        assertThat(SimHash.hamming(SimHash.of(screen), SimHash.of(unrelated))).isGreaterThan(9)
        assertThat(service.ingest(Models.rawCapture(unrelated))).isInstanceOf(IngestResult.Stored::class.java)
    }

    @Test
    fun `a capture not seen for six hours is not a duplicate`() = runBlocking<Unit> {
        service.ingest(Models.rawCapture(screen))
        clock.advance(IndexConfig().dedupRecentMs + 1)
        assertThat(service.ingest(Models.rawCapture(screen))).isInstanceOf(IngestResult.Stored::class.java)
    }

    @Test
    fun `a capture started more than a day ago is not a duplicate, even seen recently`() = runBlocking<Unit> {
        service.ingest(Models.rawCapture(screen))
        // Seen again every five hours: always recent, but its first sighting grows old.
        repeat(4) {
            clock.advance(5 * 3_600_000L)
            assertThat(service.ingest(Models.rawCapture(screen))).isEqualTo(IngestResult.Deduplicated(1))
        }
        clock.advance(5 * 3_600_000L) // 25 h after the first sighting
        assertThat(service.ingest(Models.rawCapture(screen))).isEqualTo(IngestResult.Stored(2))
    }

    @Test
    fun `candidates are read once per package, then kept in memory`() = runBlocking<Unit> {
        store.preset = listOf(HashRef(77, SimHash.of(screen), clock.now - 1_000, clock.now - 1_000))
        assertThat(service.ingest(Models.rawCapture(screen))).isEqualTo(IngestResult.Deduplicated(77))
        service.ingest(Models.rawCapture(screen))
        assertThat(store.recentReads).isEqualTo(1)
        service.forget()
        service.ingest(Models.rawCapture(screen))
        assertThat(store.recentReads).isEqualTo(2)
    }

    @Test
    fun `only the eight most recent captures of a package are candidates`() = runBlocking<Unit> {
        service.ingest(Models.rawCapture(screen))
        repeat(8) { i ->
            val other = (1..12).joinToString("\n") { "Écran $i, ligne $it : ${"abcdefgh"[i]}".repeat(3) }
            assertThat(service.ingest(Models.rawCapture(other))).isInstanceOf(IngestResult.Stored::class.java)
        }
        // The first capture has left the window of eight: the same screen is stored again.
        assertThat(service.ingest(Models.rawCapture(screen))).isInstanceOf(IngestResult.Stored::class.java)
    }

    @Test
    fun `a duplicate of a deleted capture is stored anew`() = runBlocking<Unit> {
        service.ingest(Models.rawCapture(screen))
        store.deleted += 1L
        assertThat(service.ingest(Models.rawCapture(screen))).isEqualTo(IngestResult.Stored(2))
        assertThat(service.ingest(Models.rawCapture(screen))).isEqualTo(IngestResult.Deduplicated(2))
    }

    /** CaptureStore with the four methods ingestion uses; any other call fails. */
    class FakeCaptureStore : CaptureStore by unused() {
        val inserted = mutableListOf<Pair<NewCapture, List<NewChunk>>>()
        val touched = mutableListOf<Pair<Long, Long>>()
        var preset: List<HashRef> = emptyList()
        var recentReads = 0
        val deleted = mutableSetOf<Long>()

        override suspend fun insert(capture: NewCapture, chunks: List<NewChunk>): Long {
            inserted += capture to chunks
            return inserted.size.toLong()
        }

        override suspend fun touch(captureId: Long, seenAt: Long): Boolean {
            if (captureId in deleted) return false
            touched += captureId to seenAt
            return true
        }

        override suspend fun recentHashes(packageName: String, limit: Int): List<HashRef> {
            recentReads++
            return preset.take(limit)
        }

        private companion object {
            fun unused(): CaptureStore = Proxy.newProxyInstance(
                CaptureStore::class.java.classLoader,
                arrayOf(CaptureStore::class.java),
            ) { _, method, _ -> error("unexpected call to ${method.name}") } as CaptureStore
        }
    }
}
