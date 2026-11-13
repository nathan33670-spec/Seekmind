package fr.corsicajack.seekmind.feature.search

import fr.corsicajack.seekmind.core.model.Relaxation
import fr.corsicajack.seekmind.core.model.SearchFilters
import fr.corsicajack.seekmind.core.model.SearchHit
import fr.corsicajack.seekmind.core.model.TimeRange
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Label of an application, from its package name; called off the main thread. */
fun interface AppLabels {
    fun label(packageName: String): String
}

/** The « Période » chips of docs/specs/ui-ux.md §3.2. */
enum class Period {
    TODAY,
    YESTERDAY,
    WEEK,
    MONTH,
    ;

    /** Inclusive bounds around [now] in [zone]: calendar days for today and yesterday, sliding windows otherwise. */
    fun range(now: Long, zone: ZoneId): TimeRange {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        fun startOf(day: LocalDate) = day.atStartOfDay(zone).toInstant().toEpochMilli()
        return when (this) {
            TODAY -> TimeRange(startOf(today), now)
            YESTERDAY -> TimeRange(startOf(today.minusDays(1)), startOf(today) - 1)
            WEEK -> TimeRange(now - 7 * DAY_MS, now)
            MONTH -> TimeRange(now - 30 * DAY_MS, now)
        }
    }

    private companion object {
        const val DAY_MS = 86_400_000L
    }
}

/** What the screen shows (docs/specs/ui-ux.md §3.2, table of states). */
enum class SearchPhase {
    /** No query: recent searches and examples. */
    IDLE,

    /** Waiting for the first results. */
    SEARCHING,
    RESULTS,
    NO_RESULT,

    /** No credit left: the credit sheet (docs/specs/monetisation.md §6). */
    NO_CREDIT,

    /** The database cannot be opened (ENF-REL-02). */
    UNAVAILABLE,
}

/** A filter the parser found in the query text, shown as a removable chip. */
sealed interface UnderstoodFilter {
    val key: String

    data class Range(val label: String, val range: TimeRange) : UnderstoodFilter {
        override val key = "range"
    }

    data class App(val packageName: String, val label: String) : UnderstoodFilter {
        override val key = "app:$packageName"
    }
}

data class SearchResultItem(val hit: SearchHit, val appLabel: String)

data class AppChoice(val packageName: String, val label: String)

data class SearchUiState(
    val query: String = "",
    val period: Period? = null,
    /** Application chosen with the chip, or null. */
    val app: String? = null,
    val apps: List<AppChoice> = emptyList(),
    val phase: SearchPhase = SearchPhase.IDLE,
    /** Keyword results shown, hybrid results pending: « Affinage… ». */
    val refining: Boolean = false,
    val results: List<SearchResultItem> = emptyList(),
    val understood: List<UnderstoodFilter> = emptyList(),
    val relaxed: Relaxation? = null,
    /** The relaxed time expression, for « Rien trouvé mardi soir — voici d'autres dates ». */
    val relaxedLabel: String? = null,
    val semanticAvailable: Boolean = true,
    /** Fragments still waiting for their vector: the semantic index is incomplete. */
    val pendingVectors: Int = 0,
    val recent: List<String> = emptyList(),
    /** The last search found nothing and was not charged. */
    val notCharged: Boolean = false,
)

/**
 * Key of a search for the entitlement gate (docs/specs/monetisation.md §2): the query with its letters
 * folded and its spaces collapsed, and the filters chosen with the chips.
 */
object QueryKey {
    fun of(query: String, filters: SearchFilters): String = buildString {
        append(query.trim().lowercase().replace(WHITESPACE, " "))
        append('|').append(filters.range?.let { "${it.from}-${it.to}" } ?: "")
        append('|').append(filters.packages.sorted().joinToString(","))
    }

    private val WHITESPACE = Regex("\\s+")
}
