package fr.corsicajack.seekmind.core.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.RandomAccessFile
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Opening according to docs/specs/stockage.md §2 (ticket M1-03). */
@RunWith(AndroidJUnit4::class)
class DatabaseOpenerTest {
    private val test = TestDatabase("opener")

    @Before
    @After
    fun clean() = test.delete()

    @Test
    fun aNewDatabaseIsReadyEncryptedAndConfigured() {
        val db = test.ready().openHelper.writableDatabase
        assertThat(db.strings("PRAGMA journal_mode").single()).isEqualTo("wal")
        assertThat(db.strings("PRAGMA foreign_keys").single()).isEqualTo("1")
        assertThat(db.strings("PRAGMA secure_delete").single()).isEqualTo("2")
        assertThat(db.strings("PRAGMA temp_store").single()).isEqualTo("2")
        val header = ByteArray(16).also { RandomAccessFile(test.file(), "r").use { file -> file.readFully(it) } }
        assertThat(String(header, Charsets.US_ASCII)).isNotEqualTo("SQLite format 3\u0000")
    }

    @Test
    fun theDatabaseReopensWithItsKey() {
        test.ready().openHelper.writableDatabase.insertCapture("texte conservé")
        closeAll()
        val db = test.ready().openHelper.writableDatabase
        assertThat(db.long("SELECT count(*) FROM capture")).isEqualTo(1)
    }

    @Test
    fun aDatabaseWithoutItsKeyFileIsUnavailable() {
        test.ready()
        closeAll()
        File(test.context.noBackupFilesDir, "opener.key").delete()
        assertThat(test.open()).isEqualTo(DatabaseState.Unavailable(UnavailableReason.KEY_MISSING))
        assertThat(test.file().exists()).isTrue() // never replaced nor repaired
    }

    @Test
    fun anUnreadableDatabaseIsUnavailableWithoutCrash() {
        test.ready().openHelper.writableDatabase.insertCapture("texte")
        closeAll()
        RandomAccessFile(test.file(), "rw").use { file -> file.write(ByteArray(4096) { 0x5a }) }
        assertThat(test.open()).isEqualTo(DatabaseState.Unavailable(UnavailableReason.UNREADABLE))
    }

    @Test
    fun aLostKeystoreKeyIsUnavailable() {
        test.ready()
        closeAll()
        fr.corsicajack.seekmind.core.database.key.AndroidKeystoreWrapper("seekmind.db.kek.test.opener").delete()
        assertThat(test.open()).isEqualTo(DatabaseState.Unavailable(UnavailableReason.KEY_INVALID))
    }

    private fun closeAll() = test.closeAll()
}
