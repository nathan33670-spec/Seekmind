package fr.corsicajack.seekmind.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import fr.corsicajack.seekmind.core.common.Clock
import fr.corsicajack.seekmind.core.database.settings.SettingsRepository
import fr.corsicajack.seekmind.lock.AppLock
import fr.corsicajack.seekmind.lock.KeyguardDeviceSecurity
import fr.corsicajack.seekmind.lock.LockSettings
import javax.inject.Singleton
import kotlinx.coroutines.flow.map

/** The application lock of the process (ticket M6-07). */
@Module
@InstallIn(SingletonComponent::class)
object LockModule {
    @Provides
    @Singleton
    fun appLock(@ApplicationContext context: Context, settings: SettingsRepository, clock: Clock): AppLock = AppLock(
        settings = settings.settings.map { LockSettings(enabled = it.lockEnabled, graceMs = it.lockGraceMs) },
        clock = clock,
        device = KeyguardDeviceSecurity(context),
    )
}
