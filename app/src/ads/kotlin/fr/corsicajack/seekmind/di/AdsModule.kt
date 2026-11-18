package fr.corsicajack.seekmind.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fr.corsicajack.seekmind.BuildConfig
import fr.corsicajack.seekmind.core.common.DefaultDispatcherProvider
import fr.corsicajack.seekmind.core.model.config.MonetizationConfig
import fr.corsicajack.seekmind.core.model.port.RewardProvider
import fr.corsicajack.seekmind.engine.ads.AdMobRewardProvider
import fr.corsicajack.seekmind.engine.ads.AdsConfig
import javax.inject.Singleton

/** Rewarded ads of the `ads` variant (docs/specs/monetisation.md §3.3, ADR-0009). */
@Module
@InstallIn(SingletonComponent::class)
object AdsModule {
    /** No daily free credit: the ads replenish the balance (docs/specs/monetisation.md §2). */
    @Provides
    @Singleton
    fun monetizationConfig(): MonetizationConfig = MonetizationConfig()

    /** Creating the provider touches no SDK: nothing runs before [RewardProvider.prepare] (§3.4). */
    @Provides
    @Singleton
    fun rewardProvider(): RewardProvider =
        AdMobRewardProvider.create(AdsConfig(BuildConfig.ADMOB_REWARDED_UNIT_ID), DefaultDispatcherProvider)
}
