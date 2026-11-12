package fr.corsicajack.seekmind.core.database

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.database.schema.FtsSchema
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The FTS5 index of docs/specs/stockage.md §3.2 on the production database (ticket M1-03): the tests of
 * prototype S1 kept as regression tests. The lexical query below is the reference form of spec §5; the
 * search store of M1-04 owns it afterwards.
 */
@RunWith(AndroidJUnit4::class)
class FtsIndexTest {
    private val test = TestDatabase("fts")
    private lateinit var db: SupportSQLiteDatabase

    @Before
    fun open() {
        test.delete()
        db = test.ready().openHelper.writableDatabase
    }

    @After
    fun clean() = test.delete()

    @Test
    fun theEngineHasFts5() {
        assertThat(db.strings("PRAGMA compile_options")).contains("ENABLE_FTS5")
        assertThat(db.strings("PRAGMA cipher_version").single()).startsWith("4.")
    }

    @Test
    fun insertTouchUpdateAndDeleteKeepTheIndexConsistent() {
        val id = db.insertCapture("premier texte")
        db.execSQL(FtsSchema.INTEGRITY_CHECK)

        val segments = db.long("SELECT count(*) FROM capture_fts_data")
        db.execSQL("UPDATE capture SET last_seen_at = 9000, seen_count = seen_count + 1 WHERE id = ?", arrayOf(id))
        assertThat(db.long("SELECT count(*) FROM capture_fts_data")).isEqualTo(segments) // touch reindexes nothing
        db.execSQL(FtsSchema.INTEGRITY_CHECK)

        db.execSQL("UPDATE capture SET text = 'second texte' WHERE id = ?", arrayOf(id))
        assertThat(match("premier")).isEmpty()
        assertThat(match("second")).containsExactly(id)
        db.execSQL(FtsSchema.INTEGRITY_CHECK)

        db.execSQL("DELETE FROM capture WHERE id = ?", arrayOf(id))
        assertThat(match("second")).isEmpty()
        db.execSQL(FtsSchema.INTEGRITY_CHECK)
    }

    @Test
    fun bm25RanksAndSnippetMarksTheMatchedTerms() {
        val long = db.insertCapture("truffe " + List(60) { "mot$it" }.joinToString(" "))
        val short = db.insertCapture("truffe noire et truffe blanche")
        db.insertCapture("aucun rapport avec le sujet")
        val rows = lexical("truffe")
        assertThat(rows.map { it.first }).containsExactly(short, long).inOrder()
        assertThat(rows.first().second).contains("\u0002truffe\u0003")
    }

    @Test
    fun matchingIgnoresDiacriticsAndCase() {
        val pates = db.insertCapture("Recette des pâtes à la truffe")
        val creme = db.insertCapture("CREME BRULEE maison")
        assertThat(match("pates")).containsExactly(pates)
        assertThat(match("PÂTES")).containsExactly(pates)
        assertThat(match("crème brûlée")).containsExactly(creme)
        assertThat(match("pât*")).containsExactly(pates)
    }

    @Test
    fun titleAndUrlAreIndexedToo() {
        val id = db.insertCapture("corps du texte", title = "Facture électricité", url = "https://example.fr/compte")
        assertThat(match("facture")).containsExactly(id)
        assertThat(match("compte")).containsExactly(id)
    }

    @Test
    fun deletingACaptureCascadesToItsChunks() {
        val id = db.insertCapture("texte à découper")
        db.execSQL("INSERT INTO chunk(capture_id, ordinal, start_offset, end_offset) VALUES (?, 0, 0, 5)", arrayOf(id))
        db.execSQL("DELETE FROM capture WHERE id = ?", arrayOf(id))
        assertThat(db.long("SELECT count(*) FROM chunk")).isEqualTo(0)
    }

    private fun match(query: String): List<Long> = lexical(query).map { it.first }

    private fun lexical(match: String): List<Pair<Long, String>> = db.query(
        """
        SELECT c.id, snippet(capture_fts, 0, char(2), char(3), '…', 24)
        FROM capture_fts
        CROSS JOIN capture c ON c.id = capture_fts.rowid
        WHERE capture_fts MATCH ? AND rank MATCH 'bm25(1.0, 2.0, 0.5)'
        ORDER BY rank
        LIMIT 20
        """.trimIndent(),
        arrayOf(match),
    ).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0) to c.getString(1)) } }
}
