package fr.corsicajack.seekmind.core.database

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The schema created by SeekMind matches the normative SQL of docs/specs/stockage.md §3.1 and §3.2
 * (ticket M1-03). The SQL is read from the spec itself, run in a reference database, and the two schemas
 * are compared through `PRAGMA table_info`, `index_list`, `index_info`, `foreign_key_list` and the SQL of
 * the FTS5 objects.
 */
@RunWith(AndroidJUnit4::class)
class SchemaConformanceTest {
    private val test = TestDatabase("schema")
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var reference: SupportSQLiteOpenHelper? = null

    @After
    fun clean() {
        reference?.close()
        context.deleteDatabase(REFERENCE)
        test.delete()
    }

    @Test
    fun theCreatedSchemaMatchesTheSpec() {
        val actual = test.ready().openHelper.writableDatabase
        val expected = referenceDatabase()
        val tables = expected.strings(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND sql NOT LIKE 'CREATE VIRTUAL%' " +
                "AND name NOT LIKE 'capture_fts%' AND name NOT LIKE 'sqlite_%' ORDER BY name",
        )
        assertThat(tables).hasSize(10)
        assertThat(actual.strings(TABLES)).containsAtLeastElementsIn(tables)

        for (table in tables) {
            assertWithMessage("columns of $table").that(columns(actual, table)).isEqualTo(columns(expected, table))
            assertWithMessage(
                "foreign keys of $table",
            ).that(foreignKeys(actual, table)).isEqualTo(foreignKeys(expected, table))
            assertWithMessage(
                "named indexes of $table",
            ).that(namedIndexes(actual, table)).isEqualTo(namedIndexes(expected, table))
            assertWithMessage(
                "unique column sets of $table",
            ).that(uniqueSets(actual, table)).isEqualTo(uniqueSets(expected, table))
        }
        val ftsObjects = "SELECT name || ': ' || sql FROM sqlite_master " +
            "WHERE (type = 'trigger' OR name = 'capture_fts') ORDER BY name"
        assertThat(actual.strings(ftsObjects).map(::normalise)).isEqualTo(expected.strings(ftsObjects).map(::normalise))
        assertThat(actual.strings(ftsObjects)).hasSize(4)
    }

    /** Statements of the first two SQL blocks of the storage spec (§3.1 tables, §3.2 full text). */
    private fun specStatements(): List<String> {
        val spec = context.assets.open("stockage.md").bufferedReader().readText()
        val blocks = Regex("```sql\\n(.*?)```", RegexOption.DOT_MATCHES_ALL).findAll(spec).map {
            it.groupValues[1]
        }.take(2).toList()
        check(blocks.size == 2) { "SQL blocks of §3.1 and §3.2 not found" }
        val statements = mutableListOf<String>()
        val current = StringBuilder()
        var inTrigger = false
        for (raw in blocks.joinToString("\n").lines()) {
            val line = raw.substringBefore("--").trimEnd()
            if (line.isBlank()) continue
            current.appendLine(line)
            if (line.trimStart().startsWith("CREATE TRIGGER")) inTrigger = true
            val ends = if (inTrigger) line.trim() == "END;" else line.endsWith(";")
            if (ends) {
                statements += current.toString().trim().removeSuffix(";")
                current.clear()
                inTrigger = false
            }
        }
        check(current.isBlank()) { "unterminated statement: $current" }
        return statements
    }

    private fun referenceDatabase(): SupportSQLiteDatabase {
        DatabaseOpener.loadSqlCipher()
        context.deleteDatabase(REFERENCE)
        val statements = specStatements()
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(REFERENCE)
            .callback(
                object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) = statements.forEach(db::execSQL)

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                },
            )
            .build()
        // An empty passphrase: a plain, unencrypted reference.
        return SupportOpenHelperFactory(ByteArray(0)).create(configuration).also { reference = it }.writableDatabase
    }

    /** name, declared type, not null, default, position in the primary key. */
    private fun columns(db: SupportSQLiteDatabase, table: String): List<String> =
        db.query("PRAGMA table_info(`$table`)").use { c ->
            buildList {
                while (c.moveToNext()) {
                    val type = c.getString(2).uppercase()
                    val pk = c.getInt(5)
                    // An INTEGER PRIMARY KEY is the rowid: never null, whether NOT NULL is written or not.
                    val notNull = if (pk > 0 && type == "INTEGER") 1 else c.getInt(3)
                    add("${c.getString(1)} $type notnull=$notNull default=${c.getString(4)} pk=$pk")
                }
            }
        }

    private fun foreignKeys(db: SupportSQLiteDatabase, table: String): Set<String> =
        db.query("PRAGMA foreign_key_list(`$table`)").use { c ->
            buildSet {
                while (c.moveToNext()) {
                    add(
                        "${c.getString(3)} -> ${c.getString(2)}.${c.getString(4)} on delete ${c.getString(6)}",
                    )
                }
            }
        }

    private fun indexes(db: SupportSQLiteDatabase, table: String): List<Triple<String, Boolean, List<String>>> =
        db.query("PRAGMA index_list(`$table`)").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(1) to (c.getInt(2) == 1)) }
        }.map { (name, unique) ->
            val columns = db.query("PRAGMA index_info(`$name`)").use { c ->
                buildList { while (c.moveToNext()) add(c.getString(2)) }
            }
            Triple(name, unique, columns)
        }

    /** Indexes the spec names (Room's own unique index for `embedding.chunk_id` is compared as a constraint). */
    private fun namedIndexes(db: SupportSQLiteDatabase, table: String): Set<String> = indexes(db, table)
        .filter { it.first.startsWith("idx_") && !(it.second && it.first == "idx_embedding_chunk") }
        .map { "${it.first} unique=${it.second} ${it.third}" }
        .toSet()

    /** Column sets under a UNIQUE constraint or a primary key, whatever the index name. */
    private fun uniqueSets(db: SupportSQLiteDatabase, table: String): Set<List<String>> = indexes(db, table)
        .filter { it.second }
        .map { it.third }
        .toSet()

    private fun normalise(sql: String) = sql.replace(Regex("\\s+"), " ").trim()

    private companion object {
        const val REFERENCE = "spec-reference.db"
        const val TABLES = "SELECT name FROM sqlite_master WHERE type = 'table'"
    }
}
