package fr.corsicajack.seekmind.core.model

import java.time.ZoneId

/** A search typed by the user (docs/specs/recherche.md §2). */
data class SearchRequest(
    val rawQuery: String,
    /** Filters chosen with the chips of the interface. */
    val filters: SearchFilters = SearchFilters(),
    val limit: Int = 50,
    val now: Long,
    val zone: ZoneId,
)

data class SearchFilters(val range: TimeRange? = null, val packages: Set<String> = emptySet())

/** Inclusive bounds, epoch ms. */
data class TimeRange(val from: Long, val to: Long)

enum class Stage { LEXICAL, HYBRID }

/** Filters dropped to find results: the time range, or every filter. */
enum class Relaxation { TIME, ALL }

enum class MatchKind { LEXICAL, SEMANTIC }

enum class QueryProfile { DEFAULT, CODE, NATURAL }

/** What the query parser understood (docs/specs/recherche.md §3). */
data class ParsedQuery(
    /** Query without the time and application expressions. */
    val text: String,
    /** Useful words, without stop words. */
    val tokens: List<String>,
    /** Tokens shaped like codes. */
    val codeTokens: List<String>,
    val range: TimeRange?,
    /** "mardi soir", for display. */
    val rangeLabel: String?,
    val packages: Set<String>,
    val profile: QueryProfile,
)

/** An extract of a capture and the character ranges to highlight in it. */
data class Snippet(val text: String, val highlights: List<IntRange>)

data class SearchHit(
    val captureId: Long,
    val score: Double,
    val packageName: String,
    val windowTitle: String?,
    val url: String?,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val snippet: Snippet,
    val textSource: Int,
    val matchedBy: Set<MatchKind>,
    /** Near-identical results folded under this one. */
    val similarCount: Int,
)

/** One emission of [fr.corsicajack.seekmind.core.model.port.SearchEngine.search]. */
data class SearchResults(
    val stage: Stage,
    val parsed: ParsedQuery,
    val hits: List<SearchHit>,
    val relaxed: Relaxation?,
    val semanticAvailable: Boolean,
)
