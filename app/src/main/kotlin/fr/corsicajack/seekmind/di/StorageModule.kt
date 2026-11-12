package fr.corsicajack.seekmind.di

import android.content.Context
import android.os.SystemClock
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import fr.corsicajack.seekmind.core.common.Clock
import fr.corsicajack.seekmind.core.database.DatabaseOpener
import fr.corsicajack.seekmind.core.database.DatabaseState
import fr.corsicajack.seekmind.core.database.DatabaseWipeService
import fr.corsicajack.seekmind.core.database.WipeService
import fr.corsicajack.seekmind.core.database.key.AndroidKeystoreWrapper
import fr.corsicajack.seekmind.core.database.key.DbKeyStore
import fr.corsicajack.seekmind.core.database.settings.SettingsRepository
import java.io.File
import javax.inject.Provider
import javax.inject.Singleton

/** The single database, key store and settings of the process (docs/specs/stockage.md §1, §2). */
@Module
@InstallIn(SingletonComponent::class)
object StorageModule {
    @Provides
    @Singleton
    fun clock(): Clock = object : Clock {
        override fun nowMillis(): Long = System.currentTimeMillis()

        // Counts deep sleep too, unlike System.nanoTime.
        override fun elapsedRealtime(): Long = SystemClock.elapsedRealtime()
    }

    @Provides
    @Singleton
    fun keyStore(@ApplicationContext context: Context): DbKeyStore =
        DbKeyStore(File(context.noBackupFilesDir, DbKeyStore.FILE_NAME), AndroidKeystoreWrapper())

    @Provides
    @Singleton
    fun opener(@ApplicationContext context: Context, keyStore: DbKeyStore): DatabaseOpener =
        DatabaseOpener(context, keyStore)

    /** Opened on first use; inject a Provider and call it off the main thread. */
    @Provides
    @Singleton
    fun database(opener: DatabaseOpener): DatabaseState = opener.open()

    @Provides
    @Singleton
    fun settings(@ApplicationContext context: Context): SettingsRepository = SettingsRepository.create(context)

    @Provides
    @Singleton
    fun wipe(
        @ApplicationContext context: Context,
        keyStore: DbKeyStore,
        settings: SettingsRepository,
        database: Provider<DatabaseState>,
    ): WipeService = DatabaseWipeService(context, keyStore, settings, current = {
        (database.get() as? DatabaseState.Ready)?.database
    })
}
