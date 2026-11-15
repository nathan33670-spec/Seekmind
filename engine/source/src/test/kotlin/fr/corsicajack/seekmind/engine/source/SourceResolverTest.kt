package fr.corsicajack.seekmind.engine.source

import android.content.Intent
import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.model.SourceLevel
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The resolution chain of docs/specs/retour-source.md §3 (ticket M7-01). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Config.TARGET_SDK])
class SourceResolverTest {
    private val apps = FakeApps(mapOf(CHROME to "Chrome", NOTES to "Notes", VIDEOS to "Vidéos"))
    private val searchRule = FakeRule(VIDEOS, SourceLevel.APP_SEARCH) { c ->
        c.windowTitle?.let { "https://videos.example/results?q=$it" }
    }
    private val resolver = ChainSourceResolver.create(apps, listOf(searchRule))

    private fun intentOf(uri: String?) = Intent.parseUri(uri, Intent.URI_INTENT_SCHEME)

    @Test
    fun aPageSeenInABrowserOpensItsUrlInThatBrowser() {
        val ref = resolver.resolve(capture(CHROME, url = "https://exemple.fr/recette?id=7"))
        assertThat(ref.level).isEqualTo(SourceLevel.EXACT_URL)
        assertThat(ref.appLabel).isEqualTo("Chrome")
        val intent = intentOf(ref.intentUri)
        assertThat(intent.action).isEqualTo(Intent.ACTION_VIEW)
        assertThat(intent.dataString).isEqualTo("https://exemple.fr/recette?id=7")
        assertThat(intent.`package`).isEqualTo(CHROME)
        assertThat(intent.categories).contains(Intent.CATEGORY_BROWSABLE)
    }

    @Test
    fun aPageOfAnUninstalledBrowserOpensInTheDefaultOne() {
        val ref = resolver.resolve(capture("org.example.oldbrowser", url = "http://exemple.fr/"))
        assertThat(ref.level).isEqualTo(SourceLevel.EXACT_URL)
        assertThat(intentOf(ref.intentUri).`package`).isNull()
        assertThat(ref.appLabel).isNull()
    }

    @Test
    fun aUrlOfAnotherSchemeIsNotOpened() {
        for (url in listOf("file:///sdcard/a.html", "content://x/y", "javascript:alert(1)", "intent://x#Intent;end")) {
            val ref = resolver.resolve(capture(CHROME, url = url))
            assertThat(ref.level).isEqualTo(SourceLevel.APP_ONLY)
            assertThat(intentOf(ref.intentUri).data).isNull()
        }
    }

    @Test
    fun aRuleOfTheApplicationComesBeforeItsHomeScreen() {
        val ref = resolver.resolve(capture(VIDEOS, title = "tarte"))
        assertThat(ref.level).isEqualTo(SourceLevel.APP_SEARCH)
        assertThat(ref.appLabel).isEqualTo("Vidéos")
        val intent = intentOf(ref.intentUri)
        assertThat(intent.dataString).isEqualTo("https://videos.example/results?q=tarte")
        assertThat(intent.`package`).isEqualTo(VIDEOS)
    }

    @Test
    fun aRuleMissingAVariableLetsTheChainGoOn() {
        val ref = resolver.resolve(capture(VIDEOS, title = null))
        assertThat(ref.level).isEqualTo(SourceLevel.APP_ONLY)
    }

    @Test
    fun aRuleWithAForbiddenSchemeIsIgnored() {
        val bad = FakeRule(VIDEOS, SourceLevel.APP_LINK, scheme = "javascript") { "javascript:alert(1)" }
        val ref = ChainSourceResolver.create(apps, listOf(bad)).resolve(capture(VIDEOS))
        assertThat(ref.level).isEqualTo(SourceLevel.APP_ONLY)
    }

    @Test
    fun otherApplicationsOpenOnTheirHomeScreen() {
        val ref = resolver.resolve(capture(NOTES))
        assertThat(ref.level).isEqualTo(SourceLevel.APP_ONLY)
        assertThat(ref.appLabel).isEqualTo("Notes")
        val intent = intentOf(ref.intentUri)
        assertThat(intent.action).isEqualTo(Intent.ACTION_MAIN)
        assertThat(intent.component?.packageName).isEqualTo(NOTES)
    }

    @Test
    fun anUninstalledApplicationIsUnavailable() {
        val ref = resolver.resolve(capture("com.example.gone"))
        assertThat(ref.level).isEqualTo(SourceLevel.UNAVAILABLE)
        assertThat(ref.intentUri).isNull()
        // A rule of an uninstalled application is not used either.
        val gone = ChainSourceResolver.create(FakeApps(emptyMap()), listOf(searchRule))
        assertThat(gone.resolve(capture(VIDEOS, title = "tarte")).level).isEqualTo(SourceLevel.UNAVAILABLE)
    }

    @Test
    fun anApplicationWithoutLauncherActivityIsUnavailable() {
        val headless = ChainSourceResolver.create(FakeApps(mapOf(NOTES to "Notes"), noLauncher = setOf(NOTES)))
        val ref = headless.resolve(capture(NOTES))
        assertThat(ref.level).isEqualTo(SourceLevel.UNAVAILABLE)
        assertThat(ref.appLabel).isEqualTo("Notes")
    }

    @Test
    fun linksOfTheText() {
        val text = "Voir https://a.example/x. Et (http://b.example/y), puis https://a.example/x encore; ftp://non " +
            (1..6).joinToString(" ") { "https://n$it.example" }
        assertThat(resolver.linksIn(capture(NOTES, text = text)))
            .containsExactly(
                "https://a.example/x",
                "http://b.example/y",
                "https://n1.example",
                "https://n2.example",
                "https://n3.example",
            ).inOrder()
        assertThat(resolver.linksIn(capture(NOTES, text = "rien, juste https:// et http://"))).isEmpty()
    }

    @Test
    fun aTappedLinkOpensInTheDefaultBrowserIfItIsWeb() {
        val ref = checkNotNull(SourceLinks.refFor("https://exemple.fr/a?b=1"))
        assertThat(ref.level).isEqualTo(SourceLevel.EXACT_URL)
        val intent = intentOf(ref.intentUri)
        assertThat(intent.dataString).isEqualTo("https://exemple.fr/a?b=1")
        assertThat(intent.`package`).isNull()
        for (link in listOf("javascript:alert(1)", "file:///a", "content://x", "intent://x#Intent;end", "geo:0,0")) {
            assertThat(SourceLinks.refFor(link)).isNull()
        }
    }
}
