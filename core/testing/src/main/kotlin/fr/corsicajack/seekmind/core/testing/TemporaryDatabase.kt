package fr.corsicajack.seekmind.core.testing

import android.content.Context
import fr.corsicajack.seekmind.core.database.DatabaseHandle
import fr.corsicajack.seekmind.core.database.DatabaseOpener
import fr.corsicajack.seekmind.core.database.DatabaseState
import fr.corsicajack.seekmind.core.database.key.AndroidKeystoreWrapper
import fr.corsicajack.seekmind.core.database.key.DbKeyStore
import java.io.File

/**
 * A real SQLCipher database for instrumented tests and benchmarks, with its own file, key file and
 * Keystore alias derived from [name]. [delete] removes all three.
 */
class TemporaryDatabase(private val context: Context, val name: String) {
    private val keyStore =
        DbKeyStore(File(context.noBackupFilesDir, "$name.key"), AndroidKeystoreWrapper("seekmind.db.kek.test.$name"))
    private var handle: DatabaseHandle? = null

    /** Opens the database, creating it if needed. */
    fun open(): DatabaseHandle = handle ?: when (val state = DatabaseOpener(context, keyStore, "$name.db").open()) {
        is DatabaseState.Ready -> state.database.also { handle = it }
        is DatabaseState.Unavailable -> error("temporary database unavailable: ${state.reason}")
    }

    fun file(): File = context.getDatabasePath("$name.db")

    fun delete() {
        handle?.close()
        handle = null
        context.deleteDatabase("$name.db")
        keyStore.destroy()
    }
}
