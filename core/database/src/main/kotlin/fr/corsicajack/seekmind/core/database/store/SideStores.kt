package fr.corsicajack.seekmind.core.database.store

import kotlinx.coroutines.flow.Flow

// Stores of the small tables of docs/specs/stockage.md §3.1 (ticket M1-05). They read and write rows;
// the rules (ceilings, free repeats, families) belong to the engines that use them.

enum class AppRuleMode(internal val code: Int) {
    EXCLUDED(1),
    INCLUDED(2),
    ;

    internal companion object {
        fun of(code: Int) = entries.first { it.code == code }
    }
}

data class AppRule(val packageName: String, val mode: AppRuleMode, val updatedAt: Long)

/** Per-application inclusion or exclusion (docs/specs/confidentialite.md §3). */
interface AppRuleStore {
    fun observe(): Flow<List<AppRule>>

    suspend fun get(packageName: String): AppRule?

    /** Creates or replaces the rule of the package. */
    suspend fun set(rule: AppRule)

    suspend fun remove(packageName: String)
}

/** A memorised secure-window probe (docs/specs/ocr.md §3.2). [activityName] is "" when unknown. */
data class SecureProbe(val packageName: String, val activityName: String, val isSecure: Boolean, val probedAt: Long)

interface SecureProbeStore {
    suspend fun get(packageName: String, activityName: String): SecureProbe?

    suspend fun put(probe: SecureProbe)

    /** Maintenance: removes the probes made before [before]. */
    suspend fun deleteOlderThan(before: Long): Int
}

/** Movements of the credit ledger (docs/specs/monetisation.md §2). */
enum class CreditReason { WELCOME, REWARD, COURTESY, DAILY, SEARCH }

data class CreditMovement(val at: Long, val delta: Int, val reason: CreditReason, val ref: String?)

/** The activation of the application: a single row (docs/specs/monetisation.md §5.4). */
data class License(val licenseId: String, val plan: String, val token: String, val activatedAt: Long)

interface MonetizationStore {
    suspend fun append(movement: CreditMovement)

    /** Sum of the deltas. */
    suspend fun balance(): Int

    fun observeBalance(): Flow<Int>

    /** Movements of [reason] made at or after [since]: daily ceilings. */
    suspend fun countSince(reason: CreditReason, since: Long): Int

    /** Time of the last movement of [reason] with [ref], or null: free repeat of a query. */
    suspend fun lastAt(reason: CreditReason, ref: String): Long?

    /** True once [reason] was recorded at least once: the welcome credits are given once. */
    suspend fun hasAny(reason: CreditReason): Boolean

    suspend fun license(): License?

    fun observeLicense(): Flow<License?>

    /** Replaces the single license row. */
    suspend fun setLicense(license: License)

    suspend fun clearLicense()
}

data class SearchHistoryEntry(
    val at: Long,
    val query: String,
    val queryKey: String,
    val resultCount: Int,
    val charged: Boolean,
)

/** Local search history (EF-SRCH-09): deletable, and not written when disabled in the settings. */
interface SearchHistoryStore {
    suspend fun add(entry: SearchHistoryEntry)

    /** Most recent first. */
    fun observeRecent(limit: Int): Flow<List<SearchHistoryEntry>>

    suspend fun clear()

    suspend fun deleteOlderThan(before: Long): Int
}

/** Daily diagnostic counters, without content (architecture §11). [day] is YYYY-MM-DD, local time. */
interface MetricsStore {
    suspend fun increment(day: String, key: String, by: Long = 1)

    /** Counters of [day], by key. */
    suspend fun day(day: String): Map<String, Long>

    /** Counters of the days from [fromDay] to [toDay] included, by day then by key. */
    suspend fun range(fromDay: String, toDay: String): Map<String, Map<String, Long>>

    /** Maintenance: removes the days strictly before [beforeDay]. */
    suspend fun deleteBefore(beforeDay: String): Int
}
