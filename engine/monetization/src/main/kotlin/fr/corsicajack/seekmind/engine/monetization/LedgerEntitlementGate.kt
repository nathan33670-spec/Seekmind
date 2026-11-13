package fr.corsicajack.seekmind.engine.monetization

import fr.corsicajack.seekmind.core.common.SmLog
import fr.corsicajack.seekmind.core.database.store.MonetizationStore
import fr.corsicajack.seekmind.core.model.Authorization
import fr.corsicajack.seekmind.core.model.Entitlement
import fr.corsicajack.seekmind.core.model.port.EntitlementGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

/**
 * [EntitlementGate] of docs/specs/monetisation.md §2, on top of [CreditLedger]:
 *
 * | Case | authorize | commit |
 * |---|---|---|
 * | Activated | Granted(false) | nothing |
 * | Same query key charged less than 10 min ago | Granted(false) | nothing |
 * | Balance ≥ 1 | Granted(true) | debit of 1 if resultCount > 0 |
 * | Balance 0 | NoCredit | — |
 *
 * [state] is shared in [scope]; it reads the license and the balance of the database.
 */
class LedgerEntitlementGate(
    private val ledger: CreditLedger,
    private val store: MonetizationStore,
    scope: CoroutineScope,
) : EntitlementGate {
    override val state: StateFlow<Entitlement> =
        combine(store.observeLicense(), ledger.balance) { license, balance ->
            license?.let { Entitlement.Activated(it.licenseId, it.plan) } ?: Entitlement.Free(balance)
        }
            .onStart { ledger.ensureWelcome() }
            .catch { error -> SmLog.w(TAG, "entitlement_state_failed", error) }
            .stateIn(scope, SharingStarted.Eagerly, Entitlement.Free(0))

    override suspend fun authorize(queryKey: String): Authorization {
        if (store.license() != null) return Authorization.Granted(willCharge = false)
        ledger.ensureWelcome()
        if (ledger.chargedRecently(queryKey)) return Authorization.Granted(willCharge = false)
        ledger.topUpDaily()
        return if (ledger.balance() >= 1) Authorization.Granted(willCharge = true) else Authorization.NoCredit
    }

    override suspend fun commit(queryKey: String, resultCount: Int) {
        // A search without results is never charged (EF-MON-04).
        if (resultCount <= 0) return
        if (store.license() != null) return
        ledger.charge(queryKey)
    }

    private companion object {
        const val TAG = "Entitlement"
    }
}
