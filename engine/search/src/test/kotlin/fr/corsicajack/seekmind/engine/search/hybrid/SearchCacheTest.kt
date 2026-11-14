package fr.corsicajack.seekmind.engine.search.hybrid

import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.model.ParsedQuery
import fr.corsicajack.seekmind.core.model.QueryProfile
import fr.corsicajack.seekmind.core.model.SearchResults
import fr.corsicajack.seekmind.core.model.Stage
import fr.corsicajack.seekmind.core.model.TimeRange
import fr.corsicajack.seekmind.core.model.config.SearchConfig
import fr.corsicajack.seekmind.core.testing.FakeClock
import org.junit.Test

class SearchCacheTest {
    private val clock = FakeClock()
    private val cache = SearchCache(SearchConfig(), clock)

    private fun parsed(text: String, range: TimeRange? = null, packages: Set<String> = emptySet()) =
        ParsedQuery(text, text.split(' '), emptyList(), range, null, packages, QueryProfile.DEFAULT)

    private fun results(text: String) = SearchResults(Stage.HYBRID, parsed(text), emptyList(), null, true)

    @Test
    fun keepsTheFiveLastResultsForTenMinutes() {
        repeat(6) { cache.put("k$it", results("q$it"), 0) }
        assertThat(cache.get("k0", 0)).isNull() // the oldest went out
        assertThat(cache.get("k5", 0)).isNotNull()
        clock.advance(600_000)
        assertThat(cache.get("k5", 0)).isNotNull()
        clock.advance(1)
        assertThat(cache.get("k5", 0)).isNull()
    }

    @Test
    fun aReadKeepsAnEntryRecent() {
        repeat(5) { cache.put("k$it", results("q$it"), 0) }
        cache.get("k0", 0)
        cache.put("k5", results("q5"), 0)
        assertThat(cache.get("k0", 0)).isNotNull()
        assertThat(cache.get("k1", 0)).isNull()
    }

    @Test
    fun aDeletionSinceTheResultWasStoredMakesItStale() {
        cache.put("k", results("q"), generation = 3)
        assertThat(cache.get("k", generation = 3)).isNotNull()
        assertThat(cache.get("k", generation = 4)).isNull()
        assertThat(cache.get("k", generation = 3)).isNull() // dropped
    }

    @Test
    fun theKeyKeepsTheStopWordsOfTheText() {
        val cheap =
            ParsedQuery(
                "restaurant pas cher",
                listOf("restaurant", "cher"),
                emptyList(),
                null,
                null,
                emptySet(),
                QueryProfile.DEFAULT,
            )
        val dear =
            ParsedQuery(
                "restaurant cher",
                listOf("restaurant", "cher"),
                emptyList(),
                null,
                null,
                emptySet(),
                QueryProfile.DEFAULT,
            )
        assertThat(SearchCache.keyOf(cheap, 50)).isNotEqualTo(SearchCache.keyOf(dear, 50))
    }

    @Test
    fun theKeyDependsOnTheWordsAndTheFilters() {
        val base = SearchCache.keyOf(parsed("recette tarte"), 50)
        assertThat(SearchCache.keyOf(parsed("recette tarte"), 50)).isEqualTo(base)
        assertThat(SearchCache.keyOf(parsed("recette tarte", range = TimeRange(1, 2)), 50)).isNotEqualTo(base)
        assertThat(SearchCache.keyOf(parsed("recette tarte", packages = setOf("a", "b")), 50))
            .isEqualTo(SearchCache.keyOf(parsed("recette tarte", packages = setOf("b", "a")), 50))
        assertThat(SearchCache.keyOf(parsed("recette"), 50)).isNotEqualTo(base)
        assertThat(SearchCache.keyOf(parsed("recette tarte"), 20)).isNotEqualTo(base)
    }
}
