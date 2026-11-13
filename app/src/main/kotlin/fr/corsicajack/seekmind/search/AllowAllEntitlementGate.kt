package fr.corsicajack.seekmind.search

import fr.corsicajack.seekmind.core.model.Authorization
import fr.corsicajack.seekmind.core.model.Entitlement
import fr.corsicajack.seekmind.core.model.port.EntitlementGate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Every search allowed, nothing charged: bound until the credit ledger of ticket M8-01 replaces it
 * (ticket M6-03).
 */
class AllowAllEntitlementGate : EntitlementGate {
    override val state: StateFlow<Entitlement> = MutableStateFlow(Entitlement.Free(balance = Int.MAX_VALUE))

    override suspend fun authorize(queryKey: String) = Authorization.Granted(willCharge = false)

    override suspend fun commit(queryKey: String, resultCount: Int) = Unit
}
