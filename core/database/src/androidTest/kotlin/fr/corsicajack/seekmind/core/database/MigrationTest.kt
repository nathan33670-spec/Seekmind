package fr.corsicajack.seekmind.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.database.key.AndroidKeystoreWrapper
import fr.corsicajack.seekmind.core.database.key.DbKeyStore
import fr.corsicajack.seekmind.core.database.key.RawKey
import java.io.File
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Migration infrastructure (docs/specs/stockage.md §7, ticket M1-03): the exported schema of version 1
 * builds a database with SQLCipher that the application then opens and validates. Each new version
 * adds a runMigrationsAndValidate test here.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val keyStore =
        DbKeyStore(
            File(context.noBackupFilesDir, "migration.key"),
            AndroidKeystoreWrapper("seekmind.db.kek.test.migration"),
        )
    private val key = RawKey.encode(
        (keyStore.getOrCreateKey(databaseExists = false) as fr.corsicajack.seekmind.core.common.Outcome.Success).value,
    )

    @get:Rule
    val helper =
        MigrationTestHelper(instrumentation, SeekMindDatabase::class.java, emptyList(), SupportOpenHelperFactory(key))

    @After
    fun clean() {
        context.deleteDatabase(NAME)
        keyStore.destroy()
    }

    @Test
    fun version1FromTheExportedSchemaOpensAndValidates() {
        DatabaseOpener.loadSqlCipher()
        helper.createDatabase(NAME, 1).close()
        val state = DatabaseOpener(context, keyStore, NAME).open()
        assertThat(state).isInstanceOf(DatabaseState.Ready::class.java)
        (state as DatabaseState.Ready).database.close()
    }

    @Test
    fun noMigrationIsDeclaredForVersion1() {
        assertThat(SeekMindDatabase.VERSION).isEqualTo(1)
        assertThat(SeekMindDatabase.MIGRATIONS).isEmpty()
    }

    private companion object {
        const val NAME = "migration.db"
    }
}
