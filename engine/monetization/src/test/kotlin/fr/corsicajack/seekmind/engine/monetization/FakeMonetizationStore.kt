package fr.corsicajack.seekmind.engine.monetization

import fr.corsicajack.seekmind.core.database.store.CreditMovement
import fr.corsicajack.seekmind.core.database.store.CreditReason
import fr.corsicajack.seekmind.core.database.store.License
import fr.corsicajack.seekmind.core.database.store.MonetizationStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** In-memory [MonetizationStore] with the semantics of the Room one. */
class FakeMonetizationStore : MonetizationStore {
    private val movements = MutableStateFlow<List<CreditMovement>>(emptyList())
    private val licenseRow = MutableStateFlow<License?>(null)

    val all: List<CreditMovement> get() = movements.value

    override suspend fun append(movement: CreditMovement) {
        movements.value = movements.value + movement
    }

    override suspend fun balance(): Int = movements.value.sumOf { it.delta }

    override fun observeBalance(): Flow<Int> = movements.map { rows -> rows.sumOf { it.delta } }

    override suspend fun countSince(reason: CreditReason, since: Long): Int =
        movements.value.count { it.reason == reason && it.at >= since }

    override suspend fun lastAt(reason: CreditReason, ref: String): Long? =
        movements.value.filter { it.reason == reason && it.ref == ref }.maxOfOrNull { it.at }

    override suspend fun hasAny(reason: CreditReason): Boolean = movements.value.any { it.reason == reason }

    override suspend fun license(): License? = licenseRow.value

    override fun observeLicense(): Flow<License?> = licenseRow

    override suspend fun setLicense(license: License) {
        licenseRow.value = license
    }

    override suspend fun clearLicense() {
        licenseRow.value = null
    }
}
