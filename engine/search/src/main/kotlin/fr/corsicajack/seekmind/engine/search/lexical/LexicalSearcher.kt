package fr.corsicajack.seekmind.engine.search.lexical

import fr.corsicajack.seekmind.core.database.store.SearchStore
import fr.corsicajack.seekmind.core.model.ParsedQuery
import fr.corsicajack.seekmind.core.model.SearchFilters
import fr.corsicajack.seekmind.core.model.Snippet
import fr.corsicajack.seekmind.core.model.config.SearchConfig

/** A capture found by keywords: its BM25 rank (smaller is better) and the extract to show. */
data class LexicalHit(val captureId: Long, val rank: Double, val snippet: Snippet)

/**
 * Keyword search (docs/specs/recherche.md §5, tickets M5-04 and M5-08): the FTS5 expressions of the parsed
 * query, the reference query of `SearchStore.lexical`, and the marked extract turned into ranges to
 * highlight. Every word first ([FtsExpression.allOf]); when that finds fewer than [minAllOfHits]
 * captures, any word ([FtsExpression.of]), whose hits follow those of the first pass. Ranking every
 * capture that holds one frequent word costs seconds on 100,000 captures (docs/mesures, M5-08).
 */
class LexicalSearcher(
    private val store: SearchStore,
    private val minAllOfHits: Int = SearchConfig().lexicalAllOfMinHits,
) {
    /** Nothing when the query has no word to look for. */
    suspend fun search(query: ParsedQuery, filters: SearchFilters, limit: Int = LIMIT): List<LexicalHit> {
        val all = FtsExpression.allOf(query) ?: return emptyList()
        val first = run(all, filters, limit)
        val any = FtsExpression.of(query)
        if (any == null || any == all || first.size >= minOf(minAllOfHits, limit)) return first
        val seen = first.mapTo(HashSet()) { it.captureId }
        // At most first.size of the `limit` rows of the second pass are duplicates: the total still reaches the limit.
        return first + run(any, filters, limit).filter { it.captureId !in seen }.take(limit - first.size)
    }

    private suspend fun run(match: String, filters: SearchFilters, limit: Int) =
        store.lexical(match, filters, limit).map { LexicalHit(it.captureId, it.rank, snippet(it.snippet)) }

    companion object {
        /** docs/specs/recherche.md §5. */
        const val LIMIT = 100

        /** Start and end of a matched term in the extract of FTS5: char(2) and char(3). */
        private val OPEN = Char(2)
        private val CLOSE = Char(3)

        /**
         * The extract without its markers, and the ranges (inclusive) of the terms they surrounded. A
         * marker left open by a cut extract closes at its end.
         */
        fun snippet(marked: String): Snippet {
            val text = StringBuilder(marked.length)
            val highlights = ArrayList<IntRange>()
            var start = -1
            for (c in marked) {
                if (c == OPEN) {
                    if (start < 0) start = text.length
                } else if (c == CLOSE) {
                    if (start >= 0 && text.length > start) highlights += start until text.length
                    start = -1
                } else {
                    text.append(c)
                }
            }
            if (start >= 0 && text.length > start) highlights += start until text.length
            return Snippet(text.toString(), highlights)
        }
    }
}
