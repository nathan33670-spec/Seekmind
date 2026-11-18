package fr.corsicajack.seekmind.di

import dagger.BindsOptionalOf
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fr.corsicajack.seekmind.core.model.port.RewardProvider
import fr.corsicajack.seekmind.engine.monetization.CreditLedger
import fr.corsicajack.seekmind.engine.monetization.RewardFlow
import java.util.Optional
import javax.inject.Singleton

/**
 * The rewarded ads (docs/specs/monetisation.md §3.2). [RewardProvider] is bound in `src/ads` only: in the
 * `offline` variant it is absent and [RewardFlow] offers no ad.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RewardModule {
    @BindsOptionalOf
    abstract fun rewardProvider(): RewardProvider

    companion object {
        @Provides
        @Singleton
        fun rewardFlow(ledger: CreditLedger, provider: Optional<RewardProvider>): RewardFlow =
            RewardFlow(ledger, provider.orElse(null))
    }
}
