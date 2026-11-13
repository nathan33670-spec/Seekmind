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
    private class Entry(val results: SearchResults, val storedAt: Long)

    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)

    @Synchronized
    fun get(key: String): SearchResults? {
        val entry = entries[key] ?: return null
        if (clock.elapsedRealtime() - entry.storedAt > config.cacheTtlMs) {
            entries.remove(key)
            return null
        }
        return entry.results
    }

    @Synchronized
    fun put(key: String, results: SearchResults) {
        entries[key] = Entry(results, clock.elapsedRealtime())
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
            append(parsed.tokens.joinToString(" "))
            append('|').append(parsed.codeTokens.joinToString(" "))
            append('|').append(parsed.range?.let { "${it.from}-${it.to}" } ?: "")
            append('|').append(parsed.packages.sorted().joinToString(","))
            append('|').append(limit)
        }
    }
}
