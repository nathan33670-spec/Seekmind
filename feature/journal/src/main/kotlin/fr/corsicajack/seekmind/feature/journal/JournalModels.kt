package fr.corsicajack.seekmind.feature.journal

import androidx.compose.runtime.Immutable
import fr.corsicajack.seekmind.core.model.CaptureSummary
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Label of an installed application; the package name when it has none or is no longer installed. */
fun interface AppLabels {
    fun label(packageName: String): String
}

/** A line of the journal (docs/specs/ui-ux.md §3.4): application, time, title or start of the text. */
@Immutable
data class JournalEntry(
    val captureId: Long,
    val lastSeenAt: Long,
    val packageName: String,
    val appLabel: String,
    val headline: String,
)

/** The lines of one day, most recent first. */
@Immutable
data class JournalDay(val date: LocalDate, val entries: List<JournalEntry>)

/** An application of the filter. */
@Immutable
data class JournalApp(val packageName: String, val label: String)

@Immutable
data class JournalUiState(
    val days: List<JournalDay> = emptyList(),
    val apps: List<JournalApp> = emptyList(),
    /** Package of the filter; null: every application. */
    val filter: String? = null,
    val loading: Boolean = true,
    val endReached: Boolean = false,
    /** The database could not be opened (ENF-REL-02): the screen says so. */
    val unavailable: Boolean = false,
    /** A deleted line waiting for its « Annuler »: hidden, not yet deleted from the database. */
    val pendingDeletion: Long? = null,
) {
    val empty: Boolean get() = !loading && days.isEmpty()
}

/** Title of the window, or else the first line of the text. */
internal fun CaptureSummary.headline(): String = windowTitle?.takeIf { it.isNotBlank() }?.trim()
    ?: preview.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()

/** Groups [entries], already sorted from the most recent, by day in [zone]. */
internal fun byDay(entries: List<JournalEntry>, zone: ZoneId): List<JournalDay> {
    val days = ArrayList<JournalDay>()
    var current: LocalDate? = null
    var lines = ArrayList<JournalEntry>()
    for (entry in entries) {
        val date = Instant.ofEpochMilli(entry.lastSeenAt).atZone(zone).toLocalDate()
        if (date != current) {
            if (current != null) days += JournalDay(current, lines)
            current = date
            lines = ArrayList()
        }
        lines += entry
    }
    if (current != null) days += JournalDay(current, lines)
    return days
}
