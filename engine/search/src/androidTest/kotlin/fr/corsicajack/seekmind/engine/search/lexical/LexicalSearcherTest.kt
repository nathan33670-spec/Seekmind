package fr.corsicajack.seekmind.engine.search.lexical

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.database.DatabaseHandle
import fr.corsicajack.seekmind.core.database.store.NewCapture
import fr.corsicajack.seekmind.core.model.SearchFilters
import fr.corsicajack.seekmind.core.testing.SyntheticDatabase
import fr.corsicajack.seekmind.core.testing.TemporaryDatabase
import fr.corsicajack.seekmind.engine.search.query.QueryParser
import fr.corsicajack.seekmind.engine.search.query.StopWords
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Keyword search on the real SQLCipher and FTS5 database (ticket M5-04). */
@RunWith(AndroidJUnit4::class)
class LexicalSearcherTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val database = TemporaryDatabase(context, "lexical_searcher")
    private lateinit var handle: DatabaseHandle
    private lateinit var searcher: LexicalSearcher
    private val parser by lazy { QueryParser(StopWords.load(context.resources)) }

    @Before
    fun setUp() {
        handle = database.open()
        searcher = LexicalSearcher(handle.search)
    }

    @After
    fun tearDown() = database.delete()

    private fun search(query: String) = runBlocking {
        searcher.search(parser.parse(query, now = AT, zone = ZoneId.of("Europe/Paris")), SearchFilters())
    }

    private fun insert(text: String, at: Long = AT, title: String? = null) = runBlocking {
        handle.captures.insert(NewCapture(at, at, "com.example.notes", null, title, null, text, 1, 0L), emptyList())
    }

    @Test
    fun hostileInputsNeverBreakTheQuery() {
        insert("Une ligne ordinaire avec des pâtes à la truffe.")
        val hostile = listOf(
            "\"", "\"\"", "a\"b", "\" OR 1=1 --", "(truffe", "truffe)", "NEAR(pâtes truffe, 2)", "pâtes NEAR truffe",
            "*", "truffe*", "text:truffe", "{text title}: truffe", "^truffe", "pâtes AND truffe", "pâtes NOT truffe",
            "-truffe", "'; DROP TABLE capture; --",
            "a${Char(
                2,
            )}b${Char(3)}c",
            "x\"* OR \"y", "truffe:", ":", "OR", "AND NOT",
        )
        hostile.forEach { query -> search(query) }
        // The table is still there, and a hostile query finds its words as plain words.
        assertThat(search("NEAR(pâtes truffe, 2)").map { it.captureId }).hasSize(1)
        assertThat(search("'; DROP TABLE capture; --")).isEmpty()
        assertThat(runBlocking { handle.captures.stats().captureCount }).isEqualTo(1)
    }

    @Test
    fun accentsDoNotMatterEitherWay() {
        val accented = insert("Réservation à l'hôtel pour l'été, café compris.")
        val plain = insert("Reservation a l'hotel pour l'ete, cafe compris.", at = AT - 1_000)

        listOf("hotel ete", "hôtel été", "réservation", "reservation", "cafe", "café").forEach { query ->
            assertThat(search(query).map { it.captureId }).containsExactly(accented, plain)
        }
    }

    @Test
    fun theExtractHighlightsTheMatchedTerms() {
        insert("Rendez-vous au petit restaurant près de la gare, celui des pâtes à la truffe.")
        val hit = search("pates truffe").single()
        val highlighted = hit.snippet.highlights.map { hit.snippet.text.substring(it.first, it.last + 1) }
        assertThat(highlighted).containsExactly("pâtes", "truffe").inOrder()
        assertThat(hit.snippet.text).doesNotContain(Char(2).toString())
    }

    @Test
    fun anExactCodeComesFirst() {
        runBlocking { SyntheticDatabase(handle).populate(CORPUS) }
        insert("Soldes d'été : summer sale, 20 % sur tout le summer store.")
        insert("Votre code promo SUMMER2026 expire bientôt.")
        insert("Summer 20 ans, summer forever, summer summer.")
        val target = insert("Code promo SUMMER20 : -20 % sur la commande, valable jusqu'à dimanche.")

        val hits = search("SUMMER20")
        assertThat(hits.first().captureId).isEqualTo(target)
        assertThat(search("code SUMMER20").first().captureId).isEqualTo(target)
    }

    private companion object {
        const val AT = 1_794_441_600_000L
        const val CORPUS = 2_000
    }
}
