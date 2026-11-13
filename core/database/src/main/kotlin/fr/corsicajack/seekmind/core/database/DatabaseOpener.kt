package fr.corsicajack.seekmind.core.database

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import fr.corsicajack.seekmind.core.common.Outcome
import fr.corsicajack.seekmind.core.common.SmLog
import fr.corsicajack.seekmind.core.database.key.DbKeyError
import fr.corsicajack.seekmind.core.database.key.DbKeyStore
import fr.corsicajack.seekmind.core.database.key.RawKey
import fr.corsicajack.seekmind.core.database.schema.FtsSchema
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/** Result of opening the database (docs/specs/stockage.md §2). */
sealed interface DatabaseState {
    data class Ready(val database: DatabaseHandle) : DatabaseState

    /** The interface offers a reset (ENF-REL-02); no repair is ever attempted. */
    data class Unavailable(val reason: UnavailableReason) : DatabaseState
}

enum class UnavailableReason {
    /** The database exists, its key file does not. */
    KEY_MISSING,

    /** The Keystore key is lost or invalidated. */
    KEY_INVALID,

    /** The key file is altered. */
    KEY_FILE_CORRUPT,

    /** SQLCipher cannot read the file with the key: wrong key, or damaged file. */
    UNREADABLE,
}

/**
 * Opens the single SQLCipher database of the process (docs/specs/stockage.md §2): raw key, WAL, the
 * pragmas of the spec on every opening, the FTS5 objects at creation. Room is built on SQLCipher's
 * factory; fallbackToDestructiveMigration is never enabled.
 */
class DatabaseOpener(
    private val context: Context,
    private val keyStore: DbKeyStore,
    private val name: String = SeekMindDatabase.NAME,
) {
    fun open(): DatabaseState {
        loadSqlCipher()
        val file = context.getDatabasePath(name)
        val key = when (val result = keyStore.getOrCreateKey(databaseExists = file.exists())) {
            is Outcome.Success -> result.value
            is Outcome.Failure -> return DatabaseState.Unavailable(result.error.toReason())
        }
        val passphrase = RawKey.encode(key)
        key.fill(0)
        val database = Room.databaseBuilder(context, SeekMindDatabase::class.java, name)
            .openHelperFactory(SupportOpenHelperFactory(passphrase))
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addMigrations(*SeekMindDatabase.MIGRATIONS)
            .addCallback(Callbacks)
            .build()
        return try {
            // Opens the file now: a wrong key or a damaged file must surface here, not on first use.
            database.openHelper.writableDatabase
            // The passphrase stays in memory while the database is open: in WAL mode, the connection
            // pool opens further read connections with it.
            DatabaseState.Ready(DatabaseHandle(database))
        } catch (error: RuntimeException) {
            SmLog.w(TAG, "database_unreadable", error)
            database.close()
            passphrase.fill(0)
            DatabaseState.Unavailable(UnavailableReason.UNREADABLE)
        }
    }

    private object Callbacks : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            FtsSchema.CREATE.forEach(db::execSQL)
        }

        override fun onOpen(db: SupportSQLiteDatabase) {
            // These pragmas return a row: run as queries, cursors closed at once.
            PRAGMAS.forEach { db.query("PRAGMA $it").close() }
        }
    }

    companion object {
        private const val TAG = "Database"

        /**
         * `synchronous = NORMAL`: with WAL, a commit no longer waits for the disk; the database cannot be
         * corrupted, only the last transactions can be lost on a power cut (spec §2).
         */
        private val PRAGMAS =
            listOf("foreign_keys = ON", "secure_delete = FAST", "temp_store = MEMORY", "synchronous = NORMAL")

        @Volatile
        private var loaded = false

        /** The native library is loaded explicitly before any opening (spec §1). */
        fun loadSqlCipher() {
            if (!loaded) {
                System.loadLibrary("sqlcipher")
                loaded = true
            }
        }

        private fun DbKeyError.toReason() = when (this) {
            DbKeyError.KeyMissing -> UnavailableReason.KEY_MISSING
            DbKeyError.AliasInvalid -> UnavailableReason.KEY_INVALID
            DbKeyError.FileCorrupt -> UnavailableReason.KEY_FILE_CORRUPT
        }
    }
}
