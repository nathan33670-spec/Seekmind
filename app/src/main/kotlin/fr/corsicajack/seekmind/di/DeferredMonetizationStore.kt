package fr.corsicajack.seekmind.di

import fr.corsicajack.seekmind.core.database.DatabaseState
import fr.corsicajack.seekmind.core.database.store.CreditMovement
import fr.corsicajack.seekmind.core.database.store.CreditReason
import fr.corsicajack.seekmind.core.database.store.License
import fr.corsicajack.seekmind.core.database.store.MonetizationStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * The [MonetizationStore] of the database, resolved on first use on [io]: injecting it does not open
 * the database. When the database is unavailable, every call fails (the search screen shows that state).
 */
internal class DeferredMonetizationStore(
    private val database: () -> DatabaseState,
    private val io: CoroutineDispatcher,
) : MonetizationStore {
    private suspend fun store(): MonetizationStore = withContext(io) {
        when (val state = database()) {
            is DatabaseState.Ready -> state.database.monetization
            is DatabaseState.Unavailable -> throw IllegalStateException("database unavailable: ${state.reason}")
        }
    }

    override suspend fun append(movement: CreditMovement) = store().append(movement)

    override suspend fun balance(): Int = store().balance()

    override fun observeBalance(): Flow<Int> = flow { emitAll(store().observeBalance()) }.flowOn(io)

    override suspend fun countSince(reason: CreditReason, since: Long): Int = store().countSince(reason, since)

    override suspend fun lastAt(reason: CreditReason, ref: String): Long? = store().lastAt(reason, ref)

    override suspend fun hasAny(reason: CreditReason): Boolean = store().hasAny(reason)

    override suspend fun license(): License? = store().license()

    override fun observeLicense(): Flow<License?> = flow { emitAll(store().observeLicense()) }.flowOn(io)

    override suspend fun setLicense(license: License) = store().setLicense(license)

    override suspend fun clearLicense() = store().clearLicense()
}
