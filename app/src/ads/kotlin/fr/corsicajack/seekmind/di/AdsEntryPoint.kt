package fr.corsicajack.seekmind.di

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fr.corsicajack.seekmind.core.model.port.RewardProvider
import fr.corsicajack.seekmind.search.SearchStack

/** The provider and the search observed by `NetworkSilenceTest` (monetisation §3.4). The application itself does not use it. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AdsEntryPoint {
    fun rewardProvider(): RewardProvider

    fun searchStack(): SearchStack
}
