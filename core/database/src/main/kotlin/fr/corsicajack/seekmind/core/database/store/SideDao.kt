package fr.corsicajack.seekmind.core.database.store

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import fr.corsicajack.seekmind.core.database.schema.AppRuleEntity
import fr.corsicajack.seekmind.core.database.schema.CreditLedgerEntity
import fr.corsicajack.seekmind.core.database.schema.LicenseEntity
import fr.corsicajack.seekmind.core.database.schema.MetricDailyEntity
import fr.corsicajack.seekmind.core.database.schema.SearchHistoryEntity
import fr.corsicajack.seekmind.core.database.schema.SecureProbeEntity
import kotlinx.coroutines.flow.Flow

@Dao
internal abstract class SideDao {
    @Query("SELECT * FROM app_rule ORDER BY package_name")
    abstract fun observeRules(): Flow<List<AppRuleEntity>>

    @Query("SELECT * FROM app_rule WHERE package_name = :pkg")
    abstract suspend fun rule(pkg: String): AppRuleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putRule(rule: AppRuleEntity)

    @Query("DELETE FROM app_rule WHERE package_name = :pkg")
    abstract suspend fun deleteRule(pkg: String)

    @Query("SELECT * FROM secure_probe WHERE package_name = :pkg AND activity_name = :activity")
    abstract suspend fun probe(pkg: String, activity: String): SecureProbeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putProbe(probe: SecureProbeEntity)

    @Query("DELETE FROM secure_probe WHERE probed_at < :before")
    abstract suspend fun deleteProbesBefore(before: Long): Int

    @Insert
    abstract suspend fun appendLedger(row: CreditLedgerEntity)

    @Query("SELECT COALESCE(SUM(delta), 0) FROM credit_ledger")
    abstract suspend fun balance(): Int

    @Query("SELECT COALESCE(SUM(delta), 0) FROM credit_ledger")
    abstract fun observeBalance(): Flow<Int>

    @Query("SELECT count(*) FROM credit_ledger WHERE reason = :reason AND at >= :since")
    abstract suspend fun countSince(reason: String, since: Long): Int

    @Query("SELECT max(at) FROM credit_ledger WHERE reason = :reason AND ref = :ref")
    abstract suspend fun lastAt(reason: String, ref: String): Long?

    @Query("SELECT EXISTS(SELECT 1 FROM credit_ledger WHERE reason = :reason)")
    abstract suspend fun hasAny(reason: String): Boolean

    @Query("SELECT * FROM license WHERE id = ${LicenseEntity.SINGLE_ROW_ID}")
    abstract suspend fun license(): LicenseEntity?

    @Query("SELECT * FROM license WHERE id = ${LicenseEntity.SINGLE_ROW_ID}")
    abstract fun observeLicense(): Flow<LicenseEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putLicense(license: LicenseEntity)

    @Query("DELETE FROM license")
    abstract suspend fun clearLicense()

    @Insert
    abstract suspend fun addHistory(row: SearchHistoryEntity)

    @Query("SELECT * FROM search_history ORDER BY at DESC, id DESC LIMIT :limit")
    abstract fun observeHistory(limit: Int): Flow<List<SearchHistoryEntity>>

    @Query("DELETE FROM search_history")
    abstract suspend fun clearHistory()

    @Query("DELETE FROM search_history WHERE at < :before")
    abstract suspend fun deleteHistoryBefore(before: Long): Int

    @Query(
        "INSERT INTO metric_daily(day, key, value) VALUES (:day, :key, :by) ON CONFLICT(day, key) DO UPDATE SET value = value + :by",
    )
    abstract suspend fun increment(day: String, key: String, by: Long)

    @Query("SELECT * FROM metric_daily WHERE day BETWEEN :from AND :to ORDER BY day, key")
    abstract suspend fun metrics(from: String, to: String): List<MetricDailyEntity>

    @Query("DELETE FROM metric_daily WHERE day < :before")
    abstract suspend fun deleteMetricsBefore(before: String): Int
}
