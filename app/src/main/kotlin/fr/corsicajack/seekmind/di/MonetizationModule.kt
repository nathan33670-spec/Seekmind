package fr.corsicajack.seekmind.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fr.corsicajack.seekmind.core.common.Clock
import fr.corsicajack.seekmind.core.common.DefaultDispatcherProvider
import fr.corsicajack.seekmind.core.database.DatabaseState
import fr.corsicajack.seekmind.core.database.store.MonetizationStore
import fr.corsicajack.seekmind.core.model.config.MonetizationConfig
import fr.corsicajack.seekmind.core.model.port.EntitlementGate
import fr.corsicajack.seekmind.engine.monetization.CreditLedger
import fr.corsicajack.seekmind.engine.monetization.LedgerEntitlementGate
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/** Credits and entitlements (docs/specs/monetisation.md §2). */
@Module
@InstallIn(SingletonComponent::class)
object MonetizationModule {
    @Provides
    @Singleton
    fun monetizationConfig(): MonetizationConfig = MonetizationConfig()

    /** Opens the database on first use, off the main thread. */
    @Provides
    @Singleton
    fun monetizationStore(database: Provider<DatabaseState>): MonetizationStore =
        DeferredMonetizationStore(database::get, DefaultDispatcherProvider.io)

    @Provides
    @Singleton
    fun creditLedger(store: MonetizationStore, config: MonetizationConfig, clock: Clock): CreditLedger =
        CreditLedger(store, config, clock)

    @Provides
    @Singleton
    fun entitlementGate(ledger: CreditLedger, store: MonetizationStore): EntitlementGate =
        LedgerEntitlementGate(ledger, store, CoroutineScope(SupervisorJob() + DefaultDispatcherProvider.default))
}
