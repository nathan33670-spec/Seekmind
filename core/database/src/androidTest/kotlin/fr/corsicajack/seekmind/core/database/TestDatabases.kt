package fr.corsicajack.seekmind.core.database

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import fr.corsicajack.seekmind.core.database.key.AndroidKeystoreWrapper
import fr.corsicajack.seekmind.core.database.key.DbKeyStore
import java.io.File

/** A database of its own for each test: file, key file and Keystore alias derived from [name]. */
internal class TestDatabase(val name: String) {
    val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    val keyStore =
        DbKeyStore(File(context.noBackupFilesDir, "$name.key"), AndroidKeystoreWrapper("seekmind.db.kek.test.$name"))
    val opener = DatabaseOpener(context, keyStore, "$name.db")
    private val opened = mutableListOf<DatabaseHandle>()

    fun open(): DatabaseState = opener.open().also { if (it is DatabaseState.Ready) opened += it.database }

    /** The stores of a ready database. */
    fun handle(): DatabaseHandle = (open() as? DatabaseState.Ready)?.database ?: error("database not ready")

    /** The Room database behind them, for the tests that inspect SQL directly. */
    fun ready(): SeekMindDatabase = handle().room

    /** Closes the open instances, keeping their files. */
    fun closeAll() {
        opened.forEach { it.close() }
        opened.clear()
    }

    fun file(): File = context.getDatabasePath("$name.db")

    fun delete() {
        closeAll()
        context.deleteDatabase("$name.db")
        keyStore.destroy()
    }
}

fun SupportSQLiteDatabase.strings(sql: String, vararg args: Any?): List<String> = query(sql, args).use { cursor ->
    buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
}

fun SupportSQLiteDatabase.long(sql: String, vararg args: Any?): Long = query(sql, args).use { cursor ->
    check(cursor.moveToFirst()) { "no row for $sql" }
    cursor.getLong(0)
}

/** Inserts a capture with raw SQL (the stores arrive with M1-04); returns its id. */
fun SupportSQLiteDatabase.insertCapture(
    text: String,
    at: Long = 1_000,
    pkg: String = "com.example.notes",
    title: String? = null,
    url: String? = null,
): Long {
    execSQL(
        "INSERT INTO capture(first_seen_at, last_seen_at, package_name, window_title, url, text, text_source, " +
            "simhash, char_count) VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?)",
        arrayOf<Any?>(at, at, pkg, title, url, text, text.hashCode().toLong(), text.length),
    )
    return long("SELECT last_insert_rowid()")
}
