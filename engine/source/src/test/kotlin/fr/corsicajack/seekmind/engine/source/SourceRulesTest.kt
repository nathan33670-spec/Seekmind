package fr.corsicajack.seekmind.engine.source

import android.content.Intent
import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.model.SourceLevel
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** `source_rules.json` and its rules (docs/specs/retour-source.md §4, ticket M7-02). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Config.TARGET_SDK])
class SourceRulesTest {
    private val shipped = SourceRules.parse(File("src/main/assets/${SourceRules.ASSET}").readText())
    private val testRules = SourceRules.parse(
        checkNotNull(javaClass.classLoader?.getResource("source_rules_test.json")).readText(),
    )

    private val apps = FakeApps(
        (shipped + testRules).associate { it.packageName to it.packageName.substringAfterLast('.') },
    )

    private fun intentOf(uri: String?) = Intent.parseUri(uri, Intent.URI_INTENT_SCHEME)

    @Test
    fun theInitialRulesOfTheSpecAreShipped() {
        assertThat(shipped.map { it.packageName to it.level }).containsExactly(
            "com.google.android.youtube" to SourceLevel.APP_SEARCH,
            "com.reddit.frontpage" to SourceLevel.APP_SEARCH,
            "com.instagram.android" to SourceLevel.APP_LINK,
            "com.zhiliaoapp.musically" to SourceLevel.APP_LINK,
            "com.twitter.android" to SourceLevel.APP_LINK,
            "com.google.android.apps.maps" to SourceLevel.APP_SEARCH,
            "com.spotify.music" to SourceLevel.APP_SEARCH,
        ).inOrder()
    }

    @Test
    fun aShippedRuleFillsItsVariables() {
        val source = SourceReturn(apps, shipped)
        val youtube = source.resolver.resolve(capture("com.google.android.youtube", title = "Tarte tatin facile"))
        assertThat(youtube.level).isEqualTo(SourceLevel.APP_SEARCH)
        val intent = intentOf(youtube.intentUri)
        assertThat(intent.dataString).isEqualTo("https://www.youtube.com/results?search_query=Tarte%20tatin%20facile")
        assertThat(intent.`package`).isEqualTo("com.google.android.youtube")

        val instagram = source.resolver.resolve(capture("com.instagram.android", text = "Publication de @chef.marie"))
        assertThat(instagram.level).isEqualTo(SourceLevel.APP_LINK)
        assertThat(intentOf(instagram.intentUri).dataString).isEqualTo("https://www.instagram.com/chef.marie/")
    }

    @Test
    fun aMissingVariableSkipsTheRuleAndTheChainGoesOn() {
        val source = SourceReturn(apps, shipped)
        val ref = source.resolver.resolve(capture("com.instagram.android", text = "Aucun identifiant ici"))
        assertThat(ref.level).isEqualTo(SourceLevel.APP_ONLY)
    }

    @Test
    fun addingARuleNeedsNoCode() {
        assertThat(testRules.map { it.packageName })
            .containsExactly("fr.corsicajack.seekmind.fixture", "com.example.videos").inOrder()
        val source = SourceReturn(apps, testRules)
        val ref = source.resolver.resolve(capture("fr.corsicajack.seekmind.fixture", text = "Fiche @item42"))
        assertThat(ref.level).isEqualTo(SourceLevel.APP_LINK)
        assertThat(intentOf(ref.intentUri).dataString).isEqualTo("seekmind-fixture://item/item42")
        // Its scheme is admitted by the launcher built with the same rules.
        val started = mutableListOf<Intent>()
        assertThat(source.launcher.launch(ref) { started += it }).isEqualTo(LaunchResult.Launched)
        assertThat(started.single().data?.scheme).isEqualTo("seekmind-fixture")
    }

    @Test
    fun aRuleSchemeOpensEndToEnd() {
        val source = SourceReturn(apps, shipped)
        val ref = source.resolver.resolve(capture("com.spotify.music", title = "Daft Punk"))
        assertThat(ref.level).isEqualTo(SourceLevel.APP_SEARCH)
        val started = mutableListOf<Intent>()
        assertThat(source.launcher.launch(ref) { started += it }).isEqualTo(LaunchResult.Launched)
        assertThat(started.single().dataString).isEqualTo("spotify:search:Daft%20Punk")
    }

    @Test
    fun invalidRulesAreSkippedNotTheFile() {
        // Forbidden schemes, a variable host, an unknown variable, a wrong level, a missing level, a non-object.
        assertThat(testRules.none { it.packageName == "com.example.bad" }).isTrue()
    }

    @Test
    fun anUnknownVersionOrAMalformedFileGivesNoRule() {
        assertThat(SourceRules.parse("""{"version": 2, "rules": []}""")).isEmpty()
        assertThat(SourceRules.parse("""{"rules": []}""")).isEmpty()
        assertThat(SourceRules.parse("{not json")).isEmpty()
        assertThat(SourceRules.parse("""{"version": 1}""")).isEmpty()
    }
}
