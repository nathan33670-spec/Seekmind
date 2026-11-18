package fr.corsicajack.seekmind.di

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fr.corsicajack.seekmind.core.model.config.MonetizationConfig
import fr.corsicajack.seekmind.core.model.port.EntitlementGate
import fr.corsicajack.seekmind.engine.monetization.RewardFlow
import fr.corsicajack.seekmind.engine.monetization.license.LicenseActivator

/** What the smoke test of the `offline` variant checks (ticket M8-06). The application itself does not use it. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface OfflineEntryPoint {
    fun monetizationConfig(): MonetizationConfig

    fun rewardFlow(): RewardFlow

    fun entitlements(): EntitlementGate

    fun activator(): LicenseActivator
}
