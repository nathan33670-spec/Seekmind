package fr.corsicajack.seekmind.core.database.maintenance

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.database.DatabaseHandle
import fr.corsicajack.seekmind.core.database.TestDatabase
import fr.corsicajack.seekmind.core.database.long
import fr.corsicajack.seekmind.core.database.schema.FtsSchema
import fr.corsicajack.seekmind.core.database.store.NewCapture
import fr.corsicajack.seekmind.core.database.store.NewChunk
import fr.corsicajack.seekmind.core.database.store.SearchHistoryEntry
import fr.corsicajack.seekmind.core.database.store.SecureProbe
import fr.corsicajack.seekmind.core.model.config.StorageConfig
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Retention and maintenance of docs/specs/stockage.md §6, with a simulated clock (ticket M1-06). */
@RunWith(AndroidJUnit4::class)
class StorageMaintenanceTest {
    private val test = TestDatabase("maintenance")
    private lateinit var handle: DatabaseHandle
    private val sql get() = handle.room.openHelper.writableDatabase

    @Before
    fun open() {
        test.delete()
        handle = test.handle()
    }

    @After
    fun clean() = test.delete()

    @Test
    fun capturesOutOfRetentionDisappear() = runBlocking {
        val now = 100 * DAY
        val old = insert("ancienne capture", at = now - 91 * DAY)
        val kept = insert("capture récente", at = now - 89 * DAY)
        val report = maintenance().run(now, ZoneOffset.UTC, retentionDays = 90, budgetBytes = BIG, charging = false)
        assertThat(report.expired).isEqualTo(1)
        assertThat(handle.captures.get(old)).isNull()
        assertThat(handle.captures.get(kept)).isNotNull()
        assertThat(handle.vectors.indexGeneration()).isEqualTo(1)
        sql.execSQL(FtsSchema.INTEGRITY_CHECK)
    }

    @Test
    fun unlimitedRetentionDeletesNothing() = runBlocking {
        val now = 10_000 * DAY
        insert("capture très ancienne", at = 1)
        val report = maintenance().run(now, ZoneOffset.UTC, retentionDays = null, budgetBytes = BIG, charging = false)
        assertThat(report.expired).isEqualTo(0)
        assertThat(handle.captures.stats().captureCount).isEqualTo(1)
        assertThat(handle.vectors.indexGeneration()).isEqualTo(0)
    }

    @Test
    fun overBudgetTheOldestGoUntilBackUnderTheBudget() = runBlocking {
        val texts = List(1_200) { i ->
            "capture numéro $i " +
                List(80) { w -> "mot${(i * 7 + w) % 900}" }.joinToString(" ")
        }
        texts.forEachIndexed { i, text -> insert(text, at = 1_000L + i, chunks = listOf(NewChunk(0, 0, 20))) }
        val maintenance = maintenance(StorageConfig(budgetEvictionBatch = 100))
        val used = maintenance.usedBytes()
        val budget = used * 6 / 10

        val report = maintenance.run(DAY, ZoneOffset.UTC, retentionDays = null, budgetBytes = budget, charging = false)

        assertThat(maintenance.usedBytes()).isAtMost(budget)
        val remaining = handle.captures.stats().captureCount
        assertThat(report.evicted).isEqualTo(1_200 - remaining)
        assertThat(remaining).isGreaterThan(0)
        // The oldest went first: what remains is the most recent block.
        assertThat(handle.captures.stats().oldestCaptureAt).isEqualTo(1_000L + (1_200 - remaining))
        assertThat(sql.long("SELECT count(*) FROM chunk")).isEqualTo(remaining.toLong())
        sql.execSQL(FtsSchema.INTEGRITY_CHECK)
    }

    @Test
    fun sideTablesArePurged() = runBlocking {
        val now = 200 * DAY
        handle.metrics.increment("1970-06-18", "captures") // day 168: older than 30 days
        handle.metrics.increment("1970-07-19", "captures") // day 199: kept
        handle.searchHistory.add(SearchHistoryEntry(now - 91 * DAY, "ancienne", "a|", 1, false))
        handle.searchHistory.add(SearchHistoryEntry(now - DAY, "récente", "r|", 1, false))
        handle.secureProbes.put(SecureProbe("com.example.a", "", true, now - 25 * HOUR))
        handle.secureProbes.put(SecureProbe("com.example.b", "", true, now - HOUR))
        val report = maintenance().run(now, ZoneOffset.UTC, retentionDays = null, budgetBytes = BIG, charging = false)
        assertThat(report.metricsDeleted).isEqualTo(1)
        assertThat(report.historyDeleted).isEqualTo(1)
        assertThat(report.probesDeleted).isEqualTo(1)
        assertThat(handle.secureProbes.get("com.example.b", "")).isNotNull()
    }

    @Test
    fun compactionOnlyWhileChargingAndFtsOptimisationWeekly() = runBlocking {
        val ids = List(300) { insert("texte à supprimer $it " + "x".repeat(500), at = 1_000) }
        ids.forEach { handle.captures.delete(it) }
        val maintenance = maintenance()
        val notCharging = maintenance.run(
            DAY,
            ZoneOffset.UTC,
            retentionDays = null,
            budgetBytes = BIG,
            charging = false,
        )
        assertThat(notCharging.compacted).isFalse()
        assertThat(notCharging.ftsOptimized).isTrue() // first pass: never optimised before
        val charging = maintenance.run(
            2 * DAY,
            ZoneOffset.UTC,
            retentionDays = null,
            budgetBytes = BIG,
            charging = true,
        )
        assertThat(charging.compacted).isTrue()
        assertThat(charging.ftsOptimized).isFalse() // optimised one day ago
        assertThat(sql.long("PRAGMA freelist_count")).isEqualTo(0)
        assertThat(maintenance.run(9 * DAY, ZoneOffset.UTC, null, BIG, charging = false).ftsOptimized).isTrue()
    }

    private fun maintenance(config: StorageConfig = StorageConfig()) = StorageMaintenance(handle, config)

    private suspend fun insert(text: String, at: Long, chunks: List<NewChunk> = emptyList()) = handle.captures.insert(
        NewCapture(at, at, "com.example.notes", null, null, null, text, 1, text.hashCode().toLong()),
        chunks,
    )

    private companion object {
        const val HOUR = 3_600_000L
        const val DAY = 24 * HOUR
        const val BIG = Long.MAX_VALUE
    }
}
