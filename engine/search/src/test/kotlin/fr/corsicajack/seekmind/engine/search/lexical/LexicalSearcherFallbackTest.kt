package fr.corsicajack.seekmind.engine.search.lexical

import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.database.store.FragmentSpan
import fr.corsicajack.seekmind.core.database.store.LexicalRow
import fr.corsicajack.seekmind.core.database.store.SearchStore
import fr.corsicajack.seekmind.core.model.Capture
import fr.corsicajack.seekmind.core.model.SearchFilters
import fr.corsicajack.seekmind.engine.search.query.QueryParser
import fr.corsicajack.seekmind.engine.search.query.StopWords
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Every word first, any word when that finds too little (docs/specs/recherche.md §5). */
class LexicalSearcherFallbackTest {
    private val parser = QueryParser(StopWords(emptyList()))

    private class Store(private val byExpression: Map<String, List<Long>>) : SearchStore {
        val expressions = mutableListOf<String>()

        override suspend fun lexical(match: String, filters: SearchFilters, limit: Int): List<LexicalRow> {
            expressions += match
            return byExpression[match].orEmpty().take(limit).map { LexicalRow(it, -1.0, "texte $it") }
        }

        override suspend fun captures(ids: List<Long>): List<Capture> = emptyList()

        override suspend fun fragments(embeddingIds: List<Long>): List<FragmentSpan> = emptyList()
    }

    private val all = "(\"facture\" AND \"piscine\"*)"
    private val any = "\"facture\" OR \"piscine\"*"

    private suspend fun search(store: Store, query: String = "facture piscine") = LexicalSearcher(
        store,
        minAllOfHits = 3,
    ).search(parser.parse(query, now = 0L, zone = ZoneId.of("UTC")), SearchFilters(), limit = 5)

    @Test
    fun enoughWithEveryWordSearchesOnce() = runTest {
        val store = Store(mapOf(all to listOf(1L, 2L, 3L), any to (1L..50L).toList()))
        assertThat(search(store).map { it.captureId }).containsExactly(1L, 2L, 3L).inOrder()
        assertThat(store.expressions).containsExactly(all)
    }

    @Test
    fun tooFewWithEveryWordAddsAnyWordAfterThem() = runTest {
        val store = Store(mapOf(all to listOf(7L), any to listOf(3L, 7L, 9L, 11L, 12L, 13L)))
        assertThat(search(store).map { it.captureId }).containsExactly(7L, 3L, 9L, 11L, 12L).inOrder()
        assertThat(store.expressions).containsExactly(all, any).inOrder()
    }

    @Test
    fun theDuplicatesOfTheFirstPassDoNotShortenTheList() = runTest {
        // The two captures with every word head the second pass too: the list still reaches the limit of 5.
        val store = Store(mapOf(all to listOf(1L, 2L), any to listOf(1L, 2L, 3L, 4L, 5L, 6L, 7L)))
        assertThat(search(store).map { it.captureId }).containsExactly(1L, 2L, 3L, 4L, 5L).inOrder()
    }

    @Test
    fun aSingleWordSearchesOnce() = runTest {
        val store = Store(emptyMap())
        assertThat(search(store, "facture")).isEmpty()
        assertThat(store.expressions).containsExactly("\"facture\"*")
    }
}
