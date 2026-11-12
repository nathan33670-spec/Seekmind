package fr.corsicajack.seekmind.core.database

import android.content.Context
import fr.corsicajack.seekmind.core.database.key.DbKeyStore
import fr.corsicajack.seekmind.core.database.settings.SettingsRepository

/** "Erase everything" (docs/specs/stockage.md §4, EF-PRIV-09). */
interface WipeService {
    suspend fun wipeEverything()
}

/**
 * Closes the database, deletes its files (WAL and shared memory included), the wrapped key and the
 * Keystore key, then resets the settings. The interface then closes the application: the next start
 * creates a new database with a new key.
 */
class DatabaseWipeService(
    private val context: Context,
    private val keyStore: DbKeyStore,
    private val settings: SettingsRepository,
    /** The open database of the process, if any. */
    private val current: () -> DatabaseHandle?,
    private val name: String = SeekMindDatabase.NAME,
) : WipeService {
    override suspend fun wipeEverything() {
        current()?.close()
        context.deleteDatabase(name)
        keyStore.destroy()
        settings.reset()
    }
}
