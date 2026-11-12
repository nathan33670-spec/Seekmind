package fr.corsicajack.seekmind.core.database.prototype

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import kotlin.random.Random
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.After
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Prototype S1 (ticket M0-06): Room on SQLCipher, with the FTS5 index of docs/specs/stockage.md §3.2.
 * Measurements are logged under the `SeekMindProto` tag and collected by the device-tests workflow
 * (docs/prototypes/S1-sqlcipher-fts5.md). Every text is synthetic.
 */
@RunWith(AndroidJUnit4::class)
class SqlCipherFtsPrototypeTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val opened = mutableListOf<PrototypeDatabase>()

    @Before
    fun deleteDatabase() {
        context.deleteDatabase(DB_NAME)
    }

    @After
    fun closeDatabases() {
        opened.forEach { it.close() }
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun theEngineIsSqlCipherWithFts5() {
        val db = open(KEY_A).openHelper.writableDatabase
        val options = db.strings("PRAGMA compile_options")
        val cipherVersion = db.strings("PRAGMA cipher_version").single()
        val sqliteVersion = db.strings("SELECT sqlite_version()").single()
        Log.i(TAG, "api=${Build.VERSION.SDK_INT} cipher_version=$cipherVersion sqlite_version=$sqliteVersion")

        assertThat(options).contains("ENABLE_FTS5")
        assertThat(cipherVersion).startsWith("4.")
        assertThat(db.strings("PRAGMA journal_mode").single()).isEqualTo("wal")
        assertThat(db.strings("PRAGMA foreign_keys").single()).isEqualTo("1")
        assertThat(db.strings("PRAGMA secure_delete").single()).isEqualTo("2")
        assertThat(db.strings("PRAGMA temp_store").single()).isEqualTo("2")
    }

    @Test
    fun anInsertedCaptureIsFoundByMatch() {
        val database = open(KEY_A)
        val id = database.captures().insert(capture("La réunion du comité aura lieu jeudi."))

        val rows = lexical(database, "comité")

        assertThat(rows.map { it.id }).containsExactly(id)
        assertThat(lexical(database, "vendredi")).isEmpty()
    }

    @Test
    fun bm25RanksAndSnippetMarksTheMatchedTerms() {
        val database = open(KEY_A)
        val dao = database.captures()
        val long = dao.insert(capture("truffe " + filler(60)))
        val short = dao.insert(capture("truffe noire et truffe blanche"))
        dao.insert(capture("aucun rapport avec le sujet"))

        val rows = lexical(database, "truffe")

        assertThat(rows.map { it.id }).containsExactly(short, long).inOrder()
        assertThat(rows.all { it.rank < 0.0 }).isTrue()
        assertThat(rows.first().snippet).contains("\u0002truffe\u0003")
    }

    @Test
    fun matchingIgnoresDiacriticsAndCase() {
        val database = open(KEY_A)
        val dao = database.captures()
        val pates = dao.insert(capture("Recette des pâtes à la truffe"))
        val creme = dao.insert(capture("CREME BRULEE maison"))

        assertThat(lexical(database, "pates").map { it.id }).containsExactly(pates)
        assertThat(lexical(database, "PÂTES").map { it.id }).containsExactly(pates)
        assertThat(lexical(database, "crème brûlée").map { it.id }).containsExactly(creme)
        assertThat(lexical(database, "pât*").map { it.id }).containsExactly(pates)
    }

    @Test
    fun filtersOnDatesAndPackagesApply() {
        val database = open(KEY_A)
        val dao = database.captures()
        val old = dao.insert(capture("facture électricité", at = 1_000, pkg = "com.example.mail"))
        val recent = dao.insert(capture("facture électricité", at = 5_000, pkg = "com.example.browser"))

        assertThat(lexical(database, "facture", from = 2_000).map { it.id }).containsExactly(recent)
        assertThat(lexical(database, "facture", to = 2_000).map { it.id }).containsExactly(old)
        assertThat(lexical(database, "facture", packages = listOf("com.example.mail")).map { it.id })
            .containsExactly(old)
    }

    @Test
    fun triggersKeepTheIndexInStepWithTheTable() {
        val database = open(KEY_A)
        val dao = database.captures()
        val db = database.openHelper.writableDatabase
        val id = dao.insert(capture("premier texte"))

        val segmentsBefore = db.long("SELECT count(*) FROM capture_fts_data")
        dao.touch(id, seenAt = 9_000)
        assertThat(db.long("SELECT count(*) FROM capture_fts_data")).isEqualTo(segmentsBefore)
        checkIndexIntegrity(db)

        dao.replaceText(id, "second texte")
        assertThat(lexical(database, "premier")).isEmpty()
        assertThat(lexical(database, "second").map { it.id }).containsExactly(id)
        checkIndexIntegrity(db)

        dao.delete(id)
        assertThat(lexical(database, "second")).isEmpty()
        checkIndexIntegrity(db)
    }

    @Test
    fun theFileIsUnreadableWithoutTheRightKey() {
        open(KEY_A).also { it.captures().insert(capture("contenu chiffré de test")) }.close()
        opened.clear()

        // InputStream.readNBytes needs API 33: read the header by hand.
        val header = ByteArray(16)
        context.getDatabasePath(DB_NAME).inputStream().use { input -> check(input.read(header) == header.size) }
        assertThat(String(header, Charsets.US_ASCII)).isNotEqualTo("SQLite format 3\u0000")

        val wrongKey = runCatching { open(KEY_B).captures().count() }
        assertThat(wrongKey.isFailure).isTrue()
        Log.i(TAG, "api=${Build.VERSION.SDK_INT} wrong_key_error=${wrongKey.exceptionOrNull()?.javaClass?.simpleName}")

        assertThat(open(KEY_A).captures().count()).isEqualTo(1)
    }

    @Test
    fun measuresInsertionsAndQueries() {
        val database = open(KEY_A)
        val dao = database.captures()
        val random = Random(SEED)
        val vocabulary = Vocabulary(random)

        val insertMs = DoubleArray(BENCH_ROWS) { index ->
            val row = capture(vocabulary.text(random, BENCH_CHARS), at = index.toLong())
            val start = SystemClock.elapsedRealtimeNanos()
            dao.insert(row)
            (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
        }
        measure("insert_ms_median", insertMs.percentile(0.50))
        measure("insert_ms_p95", insertMs.percentile(0.95))
        measure("insert_total_s", insertMs.sum() / 1_000.0)

        val queries = listOf(
            "one_term" to { vocabulary.word(random) },
            "two_terms" to { "${vocabulary.word(random)} ${vocabulary.word(random)}" },
            "prefix" to { vocabulary.word(random).take(3) + "*" },
        )
        for ((kind, next) in queries) {
            val queryMs = DoubleArray(BENCH_QUERIES) {
                val match = next()
                val start = SystemClock.elapsedRealtimeNanos()
                lexical(database, match, limit = 50)
                (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
            }
            measure("match_${kind}_ms_median", queryMs.percentile(0.50))
            measure("match_${kind}_ms_p95", queryMs.percentile(0.95))
        }

        database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").close()
        val file = context.getDatabasePath(DB_NAME)
        measure("db_file_mb", file.length() / 1_048_576.0)
        assertThat(dao.count()).isEqualTo(BENCH_ROWS)
    }

    private fun open(key: ByteArray): PrototypeDatabase {
        val database = Room.databaseBuilder(context, PrototypeDatabase::class.java, DB_NAME)
            .openHelperFactory(SupportOpenHelperFactory(rawKey(key)))
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addCallback(
                object : RoomDatabase.Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        FtsSchema.CREATE.forEach(db::execSQL)
                    }

                    override fun onOpen(db: SupportSQLiteDatabase) {
                        listOf("foreign_keys = ON", "secure_delete = FAST", "temp_store = MEMORY")
                            .forEach { db.query("PRAGMA $it").close() }
                    }
                },
            )
            .build()
        opened += database
        return database
    }

    private fun lexical(
        database: PrototypeDatabase,
        match: String,
        from: Long? = null,
        to: Long? = null,
        packages: List<String> = emptyList(),
        limit: Int = 20,
    ): List<LexicalRow> {
        val args = listOf<Any?>(match, from, from, to, to, if (packages.isEmpty()) 0 else 1) + packages + limit
        val sql = FtsSchema.lexicalQuery(packages.size)
        return database.openHelper.readableDatabase.query(sql, args.toTypedArray()).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(LexicalRow(cursor.getLong(0), cursor.getDouble(1), cursor.getString(2)))
            }
        }
    }

    private fun checkIndexIntegrity(db: SupportSQLiteDatabase) {
        db.execSQL("INSERT INTO capture_fts(capture_fts, rank) VALUES ('integrity-check', 1)")
    }

    private fun measure(name: String, value: Double) {
        Log.i(TAG, "api=${Build.VERSION.SDK_INT} $name=$value")
    }

    private data class LexicalRow(val id: Long, val rank: Double, val snippet: String)

    /** Pseudo-words built from French syllables, drawn with a skewed frequency, as natural text is. */
    private class Vocabulary(random: Random) {
        private val words = List(VOCABULARY_SIZE) {
            buildString { repeat(random.nextInt(2, 5)) { append(SYLLABLES[random.nextInt(SYLLABLES.size)]) } }
        }

        fun word(random: Random): String {
            val skewed = random.nextDouble().let { it * it * it }
            return words[(skewed * words.size).toInt()]
        }

        fun text(random: Random, chars: Int): String = buildString {
            var line = 0
            while (length < chars) {
                append(word(random))
                line++
                append(if (line % 12 == 0) '\n' else ' ')
            }
        }.take(chars)
    }

    companion object {
        private const val DB_NAME = "prototype-s1.db"
        private const val TAG = "SeekMindProto"
        private const val SEED = 20_261_112L
        private const val BENCH_ROWS = 10_000
        private const val BENCH_CHARS = 600
        private const val BENCH_QUERIES = 200
        private const val VOCABULARY_SIZE = 20_000

        // Test keys, generated for this prototype only.
        private val KEY_A = ByteArray(32) { it.toByte() }
        private val KEY_B = ByteArray(32) { (31 - it).toByte() }

        private val SYLLABLES = listOf(
            "ba", "bé", "che", "co", "da", "dè", "fa", "fi", "ga", "gé", "la", "lè", "li", "ma", "mê", "mo",
            "na", "né", "pa", "pâ", "po", "ra", "ré", "ri", "sa", "sé", "ta", "té", "to", "va", "vé", "zo",
            "on", "an", "in", "eau", "ou", "ç", "tion", "ment", "re", "te", "que", "gne",
        )

        @BeforeClass
        @JvmStatic
        fun loadSqlCipher() {
            System.loadLibrary("sqlcipher")
        }

        /** SQLCipher raw-key form `x'<64 hex digits>'`: no key derivation (docs/specs/stockage.md §2). */
        private fun rawKey(key: ByteArray): ByteArray =
            ("x'" + key.joinToString("") { "%02x".format(it) } + "'").toByteArray(Charsets.US_ASCII)

        private fun capture(text: String, at: Long = 1_000, pkg: String = "com.example.notes") = CaptureEntity(
            firstSeenAt = at,
            lastSeenAt = at,
            packageName = pkg,
            activityName = null,
            windowTitle = null,
            url = null,
            text = text,
            textSource = 1,
            simhash = text.hashCode().toLong(),
            charCount = text.length,
        )

        private fun filler(words: Int) = List(words) { "mot$it" }.joinToString(" ")

        private fun DoubleArray.percentile(p: Double): Double {
            val sorted = sorted()
            return sorted[((sorted.size - 1) * p).toInt()]
        }

        private fun SupportSQLiteDatabase.strings(sql: String): List<String> = query(sql).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }

        private fun SupportSQLiteDatabase.long(sql: String): Long = query(sql).use { cursor ->
            cursor.moveToFirst()
            cursor.getLong(0)
        }
    }
}
