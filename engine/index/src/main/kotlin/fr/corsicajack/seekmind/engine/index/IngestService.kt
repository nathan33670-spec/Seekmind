package fr.corsicajack.seekmind.engine.index

import fr.corsicajack.seekmind.core.common.Clock
import fr.corsicajack.seekmind.core.database.store.CaptureStore
import fr.corsicajack.seekmind.core.database.store.HashRef
import fr.corsicajack.seekmind.core.database.store.NewCapture
import fr.corsicajack.seekmind.core.database.store.NewChunk
import fr.corsicajack.seekmind.core.model.IngestResult
import fr.corsicajack.seekmind.core.model.RawCapture
import fr.corsicajack.seekmind.core.model.config.IndexConfig
import fr.corsicajack.seekmind.core.model.port.CaptureSink

/** Asks for the vectors of pending fragments to be computed (docs/specs/indexation.md §7.1). */
fun interface EmbeddingScheduler {
    /** Makes sure the unique work `embedding-charging` is scheduled (policy KEEP): cheap to repeat. */
    fun requestCharging()
}

/**
 * Turns an accepted capture into rows (docs/specs/indexation.md §1 to §6): normalisation, SimHash,
 * deduplication against the recent captures of the same package, then one transaction for the capture,
 * its full-text entry and its pending fragments. Runs on the capture thread, which serialises the calls:
 * the per-package cache needs no lock.
 */
class IngestService(
    private val captures: CaptureStore,
    private val scheduler: EmbeddingScheduler,
    private val clock: Clock,
    private val config: IndexConfig = IndexConfig(),
    private val normalizer: TextNormalizer = TextNormalizer(config),
    private val chunker: Chunker = Chunker(config),
) : CaptureSink {
    /** The [IndexConfig.dedupWindow] most recently seen captures of each package, most recent first. */
    private val recent = HashMap<String, MutableList<HashRef>>()

    override suspend fun ingest(raw: RawCapture): IngestResult {
        val text = normalizer.normalize(raw.text)
        val simhash = SimHash.of(text)
        val now = clock.nowMillis()
        val packageName = raw.meta.packageName
        val candidates = recent.getOrPut(packageName) {
            captures.recentHashes(packageName, config.dedupWindow).toMutableList()
        }

        val duplicate = candidates.firstOrNull { isDuplicate(it, simhash, now) }
        if (duplicate != null) {
            candidates.remove(duplicate)
            // A capture deleted since it was cached is not touched: the screen is stored anew.
            if (captures.touch(duplicate.captureId, now)) {
                candidates.add(0, duplicate.copy(lastSeenAt = now))
                return IngestResult.Deduplicated(duplicate.captureId)
            }
        }

        val chunks = chunker.split(text).mapIndexed { ordinal, span -> NewChunk(ordinal, span.start, span.end) }
        val id = captures.insert(
            NewCapture(
                firstSeenAt = now,
                lastSeenAt = now,
                packageName = packageName,
                activityName = raw.meta.activityName,
                windowTitle = raw.meta.windowTitle,
                url = raw.url,
                text = text,
                textSource = raw.textSource,
                simhash = simhash,
            ),
            chunks,
        )
        candidates.add(0, HashRef(id, simhash, now, now))
        while (candidates.size > config.dedupWindow) candidates.removeAt(candidates.lastIndex)
        if (chunks.isNotEmpty()) scheduler.requestCharging()
        return IngestResult.Stored(id)
    }

    /**
     * Forgets the cached fingerprints, after captures are deleted (journal, erase everything). Optional for
     * correctness, since a deleted candidate is detected by [CaptureStore.touch], but it frees the memory.
     * Capture thread only, as [ingest].
     */
    fun forget() = recent.clear()

    /** §4: same package (the cache's key), close fingerprint, seen recently, started less than a day ago. */
    private fun isDuplicate(candidate: HashRef, simhash: Long, now: Long): Boolean =
        SimHash.hamming(candidate.simhash, simhash) <= config.dedupMaxHamming &&
            candidate.lastSeenAt >= now - config.dedupRecentMs &&
            candidate.firstSeenAt >= now - config.dedupSpanMs
}
