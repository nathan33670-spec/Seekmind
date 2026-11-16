package fr.corsicajack.seekmind.engine.search.hybrid

import fr.corsicajack.seekmind.core.common.Clock
import fr.corsicajack.seekmind.core.model.ParsedQuery
import fr.corsicajack.seekmind.core.model.SearchResults
import fr.corsicajack.seekmind.core.model.config.SearchConfig

/**
 * The last [SearchConfig.cacheSize] hybrid results, kept [SearchConfig.cacheTtlMs] (docs/specs/recherche.md
 * §9). Thread-safe. In memory only: the results hold captured text (INV-06).
 */
class SearchCache(private val config: SearchConfig, private val clock: Clock) {
    private class Entry(val results: SearchResults, val storedAt: Long, val generation: Long)

    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)

    /**
     * The results stored under [key] at the same [generation] (`meta.index_generation`, incremented by
     * every deletion): a deleted capture never comes back from the cache.
     */
    @Synchronized
    fun get(key: String, generation: Long): SearchResults? {
        val entry = entries[key] ?: return null
        if (entry.generation != generation || clock.elapsedRealtime() - entry.storedAt > config.cacheTtlMs) {
            entries.remove(key)
            return null
        }
        return entry.results
    }

    @Synchronized
    fun put(key: String, results: SearchResults, generation: Long) {
        entries[key] = Entry(results, clock.elapsedRealtime(), generation)
        while (entries.size > config.cacheSize) entries.remove(entries.keys.first())
    }

    @Synchronized
    fun clear() = entries.clear()

    companion object {
        /**
         * `queryKey`: the normalised query and its filters, independent of the wording of the expressions
         * (docs/specs/monetisation.md §2 counts repeated queries with it).
         */
        fun keyOf(parsed: ParsedQuery, limit: Int): String = buildString {
            // The text too: the semantic branch reads it, stop words included (« pas cher » ≠ « cher »).
            append(parsed.text.trim().lowercase().replace(WHITESPACE, " "))
            append('|').append(parsed.tokens.joinToString(" "))
            append('|').append(parsed.codeTokens.joinToString(" "))
            append('|').append(parsed.range?.let { "${it.from}-${it.to}" } ?: "")
            append('|').append(parsed.packages.sorted().joinToString(","))
            append('|').append(limit)
        }

        private val WHITESPACE = Regex("\\s+")
    }
}
