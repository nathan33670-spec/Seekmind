package fr.corsicajack.seekmind.engine.search.query

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import fr.corsicajack.seekmind.core.model.QueryProfile
import fr.corsicajack.seekmind.core.model.SearchFilters
import fr.corsicajack.seekmind.core.model.SearchRequest
import fr.corsicajack.seekmind.core.model.TimeRange
import java.io.File
import java.time.ZoneId
import org.junit.Test

/** docs/specs/recherche.md §2, as a table of cases (ticket M5-01). */
class QueryParserTest {
    private val stopWords = StopWords(
        listOf("search_stopwords_fr.txt", "search_stopwords_en.txt").flatMap {
            StopWords.parse(File("src/main/res/raw/$it").readText())
        },
    )
    private val parser = QueryParser(stopWords)
    private val zone = ZoneId.of("Europe/Paris")
    private val now = 1_762_000_000_000L

    private data class Case(
        val raw: String,
        val tokens: List<String>,
        val codes: List<String> = emptyList(),
        val profile: QueryProfile = QueryProfile.DEFAULT,
    )

    private val cases = listOf(
        // Stop words removed, French and English, diacritics and case folded like FTS5.
        Case("resto italien pâtes truffe", listOf("resto", "italien", "pates", "truffe")),
        Case("la recette des Crêpes", listOf("recette", "crepes")),
        Case("j'ai vu un article sur l'électricité", listOf("article", "electricite")),
        Case("l’hôtel qu’on a aperçu", listOf("hotel")),
        Case("the price of the flight I saw", listOf("price", "flight")),
        Case("I got a message from Paul", listOf("message", "paul")),
        Case("reçu trouvé lu", listOf("recu", "trouve", "lu")),
        // A query made only of stop words is kept whole.
        Case("le la les", listOf("le", "la", "les")),
        Case("what is this", listOf("what", "is", "this")),
        Case("vu", listOf("vu")),
        // Separators as in FTS5: punctuation, hyphens, apostrophes, quotes.
        Case("« carte grise » — préfecture!", listOf("carte", "grise", "prefecture")),
        Case("week-end   Bretagne\t\n", listOf("week", "end", "bretagne")),
        Case("café café CAFÉ", listOf("cafe")),
        // Code-shaped tokens.
        Case("code SUMMER20", listOf("code", "summer20"), listOf("SUMMER20"), QueryProfile.CODE),
        Case("colis 6A12345678901", listOf("colis", "6a12345678901"), listOf("6A12345678901"), QueryProfile.CODE),
        Case(
            "suivi 1Z999AA10123456784.",
            listOf("suivi", "1z999aa10123456784"),
            listOf("1Z999AA10123456784"),
            QueryProfile.CODE,
        ),
        Case("ref AB-12_3x", listOf("ref", "ab", "12", "3x"), listOf("AB-12_3x"), QueryProfile.CODE),
        Case("(PROMO10)", listOf("promo10"), listOf("PROMO10"), QueryProfile.CODE),
        // Not codes: too short, no digit, foreign characters.
        Case("A1234", listOf("a1234"), listOf("A1234"), QueryProfile.CODE),
        Case("AB12 2026", listOf("ab12", "2026")),
        Case("bonjour-monde", listOf("bonjour", "monde")),
        Case("12/03/2026", listOf("12", "03", "2026")),
        Case("abc12@x.fr", listOf("abc12", "x", "fr")),
        // Profiles.
        Case(
            "restaurant italien terrasse quartier gare",
            listOf("restaurant", "italien", "terrasse", "quartier", "gare"),
            profile = QueryProfile.NATURAL,
        ),
        Case(
            "j'ai vu une veste en cuir marron chez un vendeur",
            listOf("veste", "cuir", "marron", "vendeur"),
        ),
        Case(
            "cheap flights from london to rome in may",
            listOf("cheap", "flights", "london", "rome", "may"),
            profile = QueryProfile.NATURAL,
        ),
        Case(
            "code PROMO25 restaurant italien terrasse quartier gare",
            listOf("code", "promo25", "restaurant", "italien", "terrasse", "quartier", "gare"),
            listOf("PROMO25"),
            QueryProfile.CODE,
        ),
        // Empty queries.
        Case("", emptyList()),
        Case("   ", emptyList()),
        Case("?!", emptyList()),
    )

    @Test
    fun `table of cases`() {
        for (case in cases) {
            val parsed = parser.parse(case.raw, now, zone)
            assertWithMessage("tokens of <${case.raw}>").that(parsed.tokens).isEqualTo(case.tokens)
            assertWithMessage("codes of <${case.raw}>").that(parsed.codeTokens).isEqualTo(case.codes)
            assertWithMessage("profile of <${case.raw}>").that(parsed.profile).isEqualTo(case.profile)
            assertWithMessage("range of <${case.raw}>").that(parsed.range).isNull()
            assertWithMessage("packages of <${case.raw}>").that(parsed.packages).isEmpty()
        }
    }

    @Test
    fun `typographic quotes and spaces are normalised in the text`() {
        val raw = "  l" + Char(0x2019) + "hôtel" + Char(0x00A0) + Char(0x00AB) + "Le Lac" + Char(0x00BB) +
            "\t " + Char(0x201C) + "x" + Char(0x201D) + "  "
        assertThat(parser.parse(raw, now, zone).text).isEqualTo("l'hôtel \"Le Lac\" \"x\"")
    }

    @Test
    fun `decomposed accents are composed`() {
        val decomposed = "cre" + Char(0x0301) + "pe"
        val parsed = parser.parse(decomposed, now, zone)
        assertThat(parsed.text).isEqualTo("crépe")
        assertThat(parsed.tokens).containsExactly("crepe")
    }

    @Test
    fun `the time expression is removed from the text`() {
        val range = TimeRange(1, 2)
        val temporal = TemporalExtractor { text, n, z ->
            assertThat(n).isEqualTo(now)
            assertThat(z).isEqualTo(zone)
            TemporalMatch(range, "mardi soir", text.replace("mardi soir", ""))
        }
        val parsed = QueryParser(stopWords, temporal).parse("recette  vue mardi soir pâtes", now, zone)
        assertThat(parsed.text).isEqualTo("recette vue pâtes")
        assertThat(parsed.tokens).containsExactly("recette", "pates").inOrder()
        assertThat(parsed.range).isEqualTo(range)
        assertThat(parsed.rangeLabel).isEqualTo("mardi soir")
    }

    @Test
    fun `the application is extracted after the time expression`() {
        val seen = mutableListOf<String>()
        val temporal =
            TemporalExtractor { text, _, _ -> TemporalMatch(TimeRange(1, 2), "hier", text.replace("hier", "")) }
        val apps = AppExtractor { text ->
            seen += text
            AppMatch(setOf("com.instagram.android"), text.replace("sur insta", ""))
        }
        val parsed = QueryParser(stopWords, temporal).parse("robe rouge sur insta hier", now, zone, apps)
        assertThat(seen).containsExactly("robe rouge sur insta")
        assertThat(parsed.text).isEqualTo("robe rouge")
        assertThat(parsed.tokens).containsExactly("robe", "rouge").inOrder()
        assertThat(parsed.packages).containsExactly("com.instagram.android")
    }

    @Test
    fun `explicit filters take precedence over those of the text`() {
        val extracted = TimeRange(1, 2)
        val chosen = TimeRange(10, 20)
        val temporal = TemporalExtractor { text, _, _ -> TemporalMatch(extracted, "hier", text.replace("hier", "")) }
        val apps = AppExtractor { text -> AppMatch(setOf("com.whatsapp"), text.replace("dans whatsapp", "")) }
        val parser = QueryParser(stopWords, temporal)

        fun request(filters: SearchFilters) =
            SearchRequest("adresse hier dans whatsapp", filters = filters, now = now, zone = zone)

        val both = parser.parse(request(SearchFilters(chosen, setOf("org.telegram.messenger"))), apps)
        assertThat(both.range).isEqualTo(chosen)
        assertThat(both.rangeLabel).isNull()
        assertThat(both.packages).containsExactly("org.telegram.messenger")
        assertThat(both.text).isEqualTo("adresse")

        val rangeOnly = parser.parse(request(SearchFilters(range = chosen)), apps)
        assertThat(rangeOnly.range).isEqualTo(chosen)
        assertThat(rangeOnly.packages).containsExactly("com.whatsapp")

        val packagesOnly = parser.parse(request(SearchFilters(packages = setOf("org.telegram.messenger"))), apps)
        assertThat(packagesOnly.range).isEqualTo(extracted)
        assertThat(packagesOnly.rangeLabel).isEqualTo("hier")
        assertThat(packagesOnly.packages).containsExactly("org.telegram.messenger")

        val none = parser.parse(request(SearchFilters()), apps)
        assertThat(none.range).isEqualTo(extracted)
        assertThat(none.packages).containsExactly("com.whatsapp")
    }

    @Test
    fun `explicit filters apply without any expression in the text`() {
        val chosen = TimeRange(10, 20)
        val request =
            SearchRequest("facture", SearchFilters(chosen, setOf("com.android.chrome")), now = now, zone = zone)
        val parsed = parser.parse(request)
        assertThat(parsed.range).isEqualTo(chosen)
        assertThat(parsed.packages).containsExactly("com.android.chrome")
        assertThat(parsed.tokens).containsExactly("facture")
    }

    @Test
    fun `stop word lists hold about 150 words each with the perception verbs`() {
        val fr = StopWords.parse(File("src/main/res/raw/search_stopwords_fr.txt").readText())
        val en = StopWords.parse(File("src/main/res/raw/search_stopwords_en.txt").readText())
        assertThat(fr.size).isAtLeast(130)
        assertThat(en.size).isAtLeast(130)
        assertThat(fr).containsAtLeast("vu", "aperçu", "lu", "reçu", "trouvé")
        assertThat(en).containsAtLeast("seen", "saw", "read", "got")
        for (word in fr + en) {
            assertWithMessage("<$word> is a single token").that(Tokenizer.tokenize(word)).hasSize(1)
        }
    }
}
