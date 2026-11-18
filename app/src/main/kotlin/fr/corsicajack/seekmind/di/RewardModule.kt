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
 * The rewarded ads (docs/specs/monetisation.md §3.2). [RewardProvider] is bound by each variant:
 * `AdMobRewardProvider` in `src/ads`, `NoRewardProvider` in `src/offline`, whose `isSupported` is false
 * so that [RewardFlow] offers no ad. Optional, so that a build without either binding still offers none.
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
