package fr.corsicajack.seekmind.engine.search.hybrid

import fr.corsicajack.seekmind.core.common.SmLog
import fr.corsicajack.seekmind.core.database.store.SearchStore
import fr.corsicajack.seekmind.core.model.ParsedQuery
import fr.corsicajack.seekmind.core.model.Relaxation
import fr.corsicajack.seekmind.core.model.SearchFilters
import fr.corsicajack.seekmind.core.model.SearchHit
import fr.corsicajack.seekmind.core.model.SearchRequest
import fr.corsicajack.seekmind.core.model.SearchResults
import fr.corsicajack.seekmind.core.model.Stage
import fr.corsicajack.seekmind.core.model.config.SearchConfig
import fr.corsicajack.seekmind.core.model.port.SearchEngine
import fr.corsicajack.seekmind.engine.search.lexical.LexicalHit
import fr.corsicajack.seekmind.engine.search.lexical.LexicalSearcher
import fr.corsicajack.seekmind.engine.search.query.AppExtractor
import fr.corsicajack.seekmind.engine.search.query.QueryParser
import fr.corsicajack.seekmind.engine.search.vector.SemanticHit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Hybrid search (docs/specs/recherche.md §1, §7 to §9).
 *
 * - The keyword and semantic branches run in parallel; the keyword results are emitted first
 *   ([Stage.LEXICAL]), then the fused results ([Stage.HYBRID]). Cancelling the flow cancels both branches.
 * - Without an embedding engine, or without text to embed: a single emission, keyword only, with
 *   `semanticAvailable = false`. Without any vector yet, or if the semantic branch fails, the
 *   [Stage.HYBRID] emission holds the keyword results with `semanticAvailable = false`.
 * - With filters and no result: the time range is dropped, then every filter ([Relaxation]).
 * - A cached query gives its hybrid results at once, in a single emission.
 */
class HybridSearcher(
    private val parser: QueryParser,
    private val apps: suspend () -> AppExtractor,
    private val lexical: LexicalSearcher,
    private val semantic: SemanticSource,
    private val store: SearchStore,
    private val cache: SearchCache,
    /** `meta.index_generation`: a deletion since a result was cached makes it stale. */
    private val generation: suspend () -> Long,
    private val config: SearchConfig = SearchConfig(),
) : SearchEngine {
    private val fusion = Fusion(config)

    override fun search(request: SearchRequest): Flow<SearchResults> = flow {
        val parsed = parser.parse(request, apps())
        val key = SearchCache.keyOf(parsed, request.limit)
        val generationAtStart = generation()
        cache.get(key, generationAtStart)?.let {
            emit(it)
            return@flow
        }
        val filters = SearchFilters(parsed.range, parsed.packages)
        val withSemantic = parsed.text.isNotBlank() && semantic.isConfigured()

        if (!withSemantic) {
            val (hits, relaxed) = relax(filters) { f -> fuse(parsed, request, lexical(parsed, f), emptyList()) }
            emit(SearchResults(Stage.LEXICAL, parsed, hits, relaxed, semanticAvailable = false))
            return@flow
        }

        coroutineScope {
            // Loading the index and embedding the query overlap the keyword search. A failure is caught
            // inside the branch: escaping the async, it would cancel the whole search.
            val query = async {
                try {
                    if (semantic.hasVectors()) semantic.queryVector(parsed.text) else null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    SmLog.w(TAG, "semantic_branch_failed", failure)
                    null
                }
            }
            val firstLexical = lexical(parsed, filters)
            emit(SearchResults(Stage.LEXICAL, parsed, fuse(parsed, request, firstLexical, emptyList()), null, true))

            val vector = query.await()
            if (vector == null) {
                val (hits, relaxed) = relax(filters) { f ->
                    fuse(parsed, request, if (f == filters) firstLexical else lexical(parsed, f), emptyList())
                }
                emit(SearchResults(Stage.HYBRID, parsed, hits, relaxed, semanticAvailable = false))
                return@coroutineScope
            }
            var semanticFailed = false
            val (hits, relaxed) = relax(filters) { f ->
                val lexicalHits = if (f == filters) firstLexical else lexical(parsed, f)
                val semanticHits = if (semanticFailed) null else semanticSearch(vector, f)
                if (semanticHits == null) semanticFailed = true
                fuse(parsed, request, lexicalHits, semanticHits.orEmpty())
            }
            val results = SearchResults(Stage.HYBRID, parsed, hits, relaxed, semanticAvailable = !semanticFailed)
            if (!semanticFailed) cache.put(key, results, generationAtStart)
            emit(results)
        }
    }

    /** The results for [filters], or, when filters are set and nothing is found, without the time range, then without any filter. */
    private suspend fun relax(
        filters: SearchFilters,
        run: suspend (SearchFilters) -> List<SearchHit>,
    ): Pair<List<SearchHit>, Relaxation?> {
        val hits = run(filters)
        if (hits.isNotEmpty() || (filters.range == null && filters.packages.isEmpty())) return hits to null
        if (filters.range != null) {
            val withoutTime = SearchFilters(range = null, packages = filters.packages)
            val relaxedHits = run(withoutTime)
            if (relaxedHits.isNotEmpty() || filters.packages.isEmpty()) {
                return relaxedHits to if (relaxedHits.isEmpty()) null else Relaxation.TIME
            }
        }
        val all = run(SearchFilters())
        return all to if (all.isEmpty()) null else Relaxation.ALL
    }

    /** Null when the index fails (database error, unexpected row): the search goes on with keywords only. */
    private suspend fun semanticSearch(vector: FloatArray, filters: SearchFilters): List<SemanticHit>? = try {
        semantic.search(vector, filters)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        SmLog.w(TAG, "semantic_search_failed", failure)
        null
    }

    private suspend fun lexical(parsed: ParsedQuery, filters: SearchFilters): List<LexicalHit> =
        lexical.search(parsed, filters, config.lexicalLimit)

    private suspend fun fuse(
        parsed: ParsedQuery,
        request: SearchRequest,
        lexicalHits: List<LexicalHit>,
        semanticHits: List<SemanticHit>,
    ): List<SearchHit> {
        val ids = (lexicalHits.map { it.captureId } + semanticHits.map { it.captureId }).distinct()
        if (ids.isEmpty()) return emptyList()
        val captures = store.captures(ids).associateBy { it.id }
        val semanticOnly = semanticHits.filter { hit -> lexicalHits.none { it.captureId == hit.captureId } }
        val fragments = if (semanticOnly.isEmpty()) {
            emptyMap()
        } else {
            store.fragments(semanticOnly.map { it.embeddingId }).associateBy { it.embeddingId }
        }
        return fusion.fuse(lexicalHits, semanticHits, captures, fragments, parsed.profile, request.now, request.limit)
    }

    private companion object {
        const val TAG = "HybridSearch"
    }
}
