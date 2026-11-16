package fr.corsicajack.seekmind.engine.search.query

import fr.corsicajack.seekmind.core.model.TimeRange
import java.time.ZoneId

/** A time expression found in the query (docs/specs/recherche.md §3). */
data class TemporalMatch(
    val range: TimeRange,
    /** "mardi soir", for display. */
    val label: String,
    /** The query with the expression and its linking words removed. */
    val remaining: String,
)

/** Extension point for the time expressions (ticket M5-02). */
fun interface TemporalExtractor {
    /** The longest time expression of [text], or null when there is none. */
    fun extract(text: String, now: Long, zone: ZoneId): TemporalMatch?

    companion object {
        /** Recognises nothing. */
        val NONE = TemporalExtractor { _, _, _ -> null }
    }
}

/** An application filter found in the query (docs/specs/recherche.md §4). */
data class AppMatch(
    val packages: Set<String>,
    /** The query with the preposition and the application name removed. */
    val remaining: String,
)

/** Extension point for the application filter (ticket M5-03), built from the installed applications. */
fun interface AppExtractor {
    /** The longest "preposition + application" expression of [text], or null when there is none. */
    fun extract(text: String): AppMatch?

    companion object {
        /** Recognises nothing. */
        val NONE = AppExtractor { null }
    }
}
