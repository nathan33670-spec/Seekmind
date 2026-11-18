package fr.corsicajack.seekmind.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fr.corsicajack.seekmind.ads.NoRewardProvider
import fr.corsicajack.seekmind.core.model.config.MonetizationConfig
import fr.corsicajack.seekmind.core.model.port.RewardProvider
import javax.inject.Singleton

/** The `offline` variant (docs/specs/monetisation.md §4, ADR-0009): no ad, daily free credits instead. */
@Module
@InstallIn(SingletonComponent::class)
object OfflineModule {
    /** The balance is topped up to 5 each calendar day, at the first search (not cumulative). */
    @Provides
    @Singleton
    fun monetizationConfig(): MonetizationConfig = MonetizationConfig(dailyFreeCredits = 5)

    @Provides
    @Singleton
    fun rewardProvider(): RewardProvider = NoRewardProvider
}
