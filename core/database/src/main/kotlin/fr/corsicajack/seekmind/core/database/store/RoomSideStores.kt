package fr.corsicajack.seekmind.core.database.store

import fr.corsicajack.seekmind.core.database.schema.AppRuleEntity
import fr.corsicajack.seekmind.core.database.schema.CreditLedgerEntity
import fr.corsicajack.seekmind.core.database.schema.LicenseEntity
import fr.corsicajack.seekmind.core.database.schema.SearchHistoryEntity
import fr.corsicajack.seekmind.core.database.schema.SecureProbeEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal class RoomAppRuleStore(private val dao: SideDao) : AppRuleStore {
    override fun observe(): Flow<List<AppRule>> = dao.observeRules().map { rows -> rows.map { it.toModel() } }

    override suspend fun get(packageName: String): AppRule? = dao.rule(packageName)?.toModel()

    override suspend fun set(rule: AppRule) =
        dao.putRule(AppRuleEntity(rule.packageName, rule.mode.code, rule.updatedAt))

    override suspend fun remove(packageName: String) = dao.deleteRule(packageName)

    private fun AppRuleEntity.toModel() = AppRule(packageName, AppRuleMode.of(mode), updatedAt)
}

internal class RoomSecureProbeStore(private val dao: SideDao) : SecureProbeStore {
    override suspend fun get(packageName: String, activityName: String): SecureProbe? =
        dao.probe(packageName, activityName)?.let {
            SecureProbe(it.packageName, it.activityName, it.isSecure, it.probedAt)
        }

    override suspend fun put(probe: SecureProbe) =
        dao.putProbe(SecureProbeEntity(probe.packageName, probe.activityName, probe.isSecure, probe.probedAt))

    override suspend fun deleteOlderThan(before: Long): Int = dao.deleteProbesBefore(before)
}

internal class RoomMonetizationStore(private val dao: SideDao) : MonetizationStore {
    override suspend fun append(movement: CreditMovement) = dao.appendLedger(
        CreditLedgerEntity(
            at = movement.at,
            delta = movement.delta,
            reason = movement.reason.name,
            ref = movement.ref,
        ),
    )

    override suspend fun balance(): Int = dao.balance()

    override fun observeBalance(): Flow<Int> = dao.observeBalance()

    override suspend fun countSince(reason: CreditReason, since: Long): Int = dao.countSince(reason.name, since)

    override suspend fun lastAt(reason: CreditReason, ref: String): Long? = dao.lastAt(reason.name, ref)

    override suspend fun hasAny(reason: CreditReason): Boolean = dao.hasAny(reason.name)

    override suspend fun license(): License? = dao.license()?.toModel()

    override fun observeLicense(): Flow<License?> = dao.observeLicense().map { it?.toModel() }

    override suspend fun setLicense(license: License) = dao.putLicense(
        LicenseEntity(
            licenseId = license.licenseId,
            plan = license.plan,
            token = license.token,
            activatedAt = license.activatedAt,
        ),
    )

    override suspend fun clearLicense() = dao.clearLicense()

    private fun LicenseEntity.toModel() = License(licenseId, plan, token, activatedAt)
}

internal class RoomSearchHistoryStore(private val dao: SideDao) : SearchHistoryStore {
    override suspend fun add(entry: SearchHistoryEntry) = dao.addHistory(
        SearchHistoryEntity(
            at = entry.at,
            query = entry.query,
            queryKey = entry.queryKey,
            resultCount = entry.resultCount,
            charged = entry.charged,
        ),
    )

    override fun observeRecent(limit: Int): Flow<List<SearchHistoryEntry>> = dao.observeHistory(limit).map { rows ->
        rows.map { SearchHistoryEntry(it.at, it.query, it.queryKey, it.resultCount, it.charged) }
    }

    override suspend fun clear() = dao.clearHistory()

    override suspend fun deleteOlderThan(before: Long): Int = dao.deleteHistoryBefore(before)
}

internal class RoomMetricsStore(private val dao: SideDao) : MetricsStore {
    override suspend fun increment(day: String, key: String, by: Long) = dao.increment(day, key, by)

    override suspend fun day(day: String): Map<String, Long> = range(day, day)[day].orEmpty()

    override suspend fun range(fromDay: String, toDay: String): Map<String, Map<String, Long>> =
        dao.metrics(fromDay, toDay).groupBy { it.day }.mapValues { (_, rows) -> rows.associate { it.key to it.value } }

    override suspend fun deleteBefore(beforeDay: String): Int = dao.deleteMetricsBefore(beforeDay)
}
