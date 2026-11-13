package fr.corsicajack.seekmind.engine.search.query

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.time.ZoneId
import org.junit.Test

class AppMatcherTest {
    private val apps = listOf(
        AppEntry("com.instagram.android", "Instagram"),
        AppEntry("com.whatsapp", "WhatsApp"),
        AppEntry("com.google.android.youtube", "YouTube"),
        AppEntry("com.android.chrome", "Chrome"),
        AppEntry("com.google.android.apps.maps", "Google Maps"),
        AppEntry("com.google.android.googlequicksearchbox", "Google"),
        AppEntry("com.example.weather", "Météo"),
        AppEntry("com.example.notes", "Notes"),
        AppEntry("com.example.notes.pro", "Notes"),
    )
    private val aliases = AppAliases.parse(File("src/main/res/raw/search_app_aliases.json").readText())
    private val matcher = AppMatcher(apps, aliases)

    private fun extract(text: String) = matcher.extract(text)?.let {
        it.packages to
            QueryParser.collapseSpaces(it.remaining)
    }

    @Test
    fun `a preposition and a label give the package, and leave the query`() {
        assertThat(extract("photo de chat sur Instagram")).isEqualTo(setOf("com.instagram.android") to "photo de chat")
        assertThat(extract("adresse dans WhatsApp")).isEqualTo(setOf("com.whatsapp") to "adresse")
        assertThat(
            extract("cooking video on YouTube"),
        ).isEqualTo(setOf("com.google.android.youtube") to "cooking video")
        assertThat(extract("recette via chrome")).isEqualTo(setOf("com.android.chrome") to "recette")
        assertThat(extract("message from whatsapp yesterday")).isEqualTo(setOf("com.whatsapp") to "message yesterday")
        assertThat(
            extract("rendez-vous depuis Notes"),
        ).isEqualTo(setOf("com.example.notes", "com.example.notes.pro") to "rendez-vous")
        assertThat(
            extract("liste de l'appli Notes"),
        ).isEqualTo(setOf("com.example.notes", "com.example.notes.pro") to "liste")
        assertThat(extract("in Chrome recipe")).isEqualTo(setOf("com.android.chrome") to "recipe")
    }

    @Test
    fun `aliases name the installed application`() {
        assertThat(extract("reel sur insta")).isEqualTo(setOf("com.instagram.android") to "reel")
        assertThat(extract("tuto on yt")).isEqualTo(setOf("com.google.android.youtube") to "tuto")
        assertThat(extract("adresse dans wa")).isEqualTo(setOf("com.whatsapp") to "adresse")
        assertThat(extract("restaurant sur gmaps")).isEqualTo(setOf("com.google.android.apps.maps") to "restaurant")
    }

    @Test
    fun `the longest name wins`() {
        assertThat(extract("pizzeria sur Google Maps")).isEqualTo(setOf("com.google.android.apps.maps") to "pizzeria")
        assertThat(
            extract("pizzeria sur Google"),
        ).isEqualTo(setOf("com.google.android.googlequicksearchbox") to "pizzeria")
    }

    @Test
    fun `case and diacritics do not matter`() {
        assertThat(extract("orage dans meteo")).isEqualTo(setOf("com.example.weather") to "orage")
        assertThat(extract("orage DANS MÉTÉO")).isEqualTo(setOf("com.example.weather") to "orage")
    }

    @Test
    fun `without a preposition nothing is extracted`() {
        assertThat(matcher.extract("chrome recette")).isNull()
        assertThat(matcher.extract("instagram")).isNull()
        assertThat(matcher.extract("photo insta")).isNull()
    }

    @Test
    fun `an application that is not installed gives no filter`() {
        assertThat(matcher.extract("vidéo sur TikTok")).isNull()
        assertThat(matcher.extract("vidéo sur tiktok")).isNull()
        assertThat(matcher.extract("message on telegram")).isNull()
        assertThat(AppMatcher(emptyList(), aliases).extract("photo sur insta")).isNull()
    }

    @Test
    fun `a preposition alone or before another word is left`() {
        assertThat(matcher.extract("sur la table")).isNull()
        assertThat(matcher.extract("on")).isNull()
    }

    @Test
    fun `the query parser applies the filter`() {
        val parser = QueryParser(StopWords(listOf("de")), TemporalParser())
        val parsed = parser.parse(
            "photo de chat sur insta hier",
            now = 1_794_441_600_000L,
            zone = ZoneId.of("Europe/Paris"),
            apps = matcher,
        )
        assertThat(parsed.packages).containsExactly("com.instagram.android")
        assertThat(parsed.text).isEqualTo("photo de chat")
        assertThat(parsed.tokens).containsExactly("photo", "chat").inOrder()
        assertThat(parsed.rangeLabel).isEqualTo("hier")
    }

    @Test
    fun `every alias of the resource names packages`() {
        assertThat(aliases).isNotEmpty()
        assertThat(aliases.keys).containsAtLeast("insta", "yt", "wa", "fb", "twitter", "x", "gmaps")
        aliases.forEach { (alias, packages) ->
            assertThat(alias).isEqualTo(alias.lowercase())
            assertThat(packages).isNotEmpty()
            packages.forEach { assertThat(it).matches("[a-z][a-z0-9_]*(\\.[a-z0-9_]+)+") }
        }
    }
}
