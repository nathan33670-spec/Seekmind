package fr.corsicajack.seekmind.engine.source

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.model.SourceLevel
import fr.corsicajack.seekmind.core.model.SourceRef
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Launching and falling back (docs/specs/retour-source.md §5, ticket M7-01). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Config.TARGET_SDK])
class SourceLauncherTest {
    private val apps = FakeApps(mapOf(CHROME to "Chrome", VIDEOS to "Vidéos", NOTES to "Notes"))
    private val launcher = SourceLauncher(apps, SourceSchemes(listOf("spotify")))
    private val started = mutableListOf<Intent>()

    /** Starts every Intent except those [refuse] matches, which throw [error]. */
    private fun starter(error: () -> RuntimeException = { ActivityNotFoundException() }, refuse: (Intent) -> Boolean) =
        { intent: Intent ->
            if (refuse(intent)) throw error()
            started += intent
        }

    private val all = starter { false }

    private fun view(uri: String, pkg: String? = null, level: SourceLevel = SourceLevel.EXACT_URL) = SourceRef(
        level = level,
        intentUri = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(pkg).toUri(Intent.URI_INTENT_SCHEME),
        appLabel = null,
    )

    @Test
    fun aPageOpensInItsBrowserInANewTask() {
        val result = launcher.launch(view("https://exemple.fr/a", CHROME), all)
        assertThat(result).isEqualTo(LaunchResult.Launched)
        val intent = started.single()
        assertThat(intent.`package`).isEqualTo(CHROME)
        assertThat(intent.dataString).isEqualTo("https://exemple.fr/a")
        assertThat(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK).isNotEqualTo(0)
    }

    @Test
    fun aBrowserThatFailsFallsBackToTheDefaultOne() {
        val result = launcher.launch(view("https://exemple.fr/a", CHROME), starter { it.`package` != null })
        assertThat(result).isEqualTo(LaunchResult.Fallback(SourceLevel.EXACT_URL))
        assertThat(started.single().`package`).isNull()
    }

    @Test
    fun aRuleThatFailsFallsBackToTheApplication() {
        val ref = view("https://videos.example/results?q=tarte", VIDEOS, SourceLevel.APP_SEARCH)
        val result = launcher.launch(ref, starter(error = { SecurityException() }) { it.action == Intent.ACTION_VIEW })
        assertThat(result).isEqualTo(LaunchResult.Fallback(SourceLevel.APP_ONLY))
        assertThat(started.single().component?.packageName).isEqualTo(VIDEOS)
    }

    @Test
    fun whenNothingOpensItFailsWithoutThrowing() {
        val never = starter { true }
        assertThat(launcher.launch(view("https://exemple.fr/a", CHROME), never)).isEqualTo(LaunchResult.Failed)
        val link = view("https://videos.example/u/1", VIDEOS, SourceLevel.APP_LINK)
        assertThat(launcher.launch(link, never)).isEqualTo(LaunchResult.Failed)
        val home = SourceRef(
            SourceLevel.APP_ONLY,
            apps.launchIntent(NOTES)!!.toUri(Intent.URI_INTENT_SCHEME),
            "Notes",
        )
        assertThat(launcher.launch(home, never)).isEqualTo(LaunchResult.Failed)
        assertThat(started).isEmpty()
    }

    @Test
    fun forbiddenSchemesAreRefused() {
        for (uri in listOf("file:///sdcard/a.html", "content://x/y", "javascript:alert(1)", "intent://x#Intent;end")) {
            assertThat(launcher.launch(view(uri), all)).isEqualTo(LaunchResult.Failed)
            assertThat(launcher.launch(view(uri, VIDEOS, SourceLevel.APP_LINK), all))
                .isEqualTo(LaunchResult.Fallback(SourceLevel.APP_ONLY))
        }
        assertThat(started.map { it.action }.distinct()).containsExactly(Intent.ACTION_MAIN)
        // Even when a rule names them.
        val permissive = SourceLauncher(apps, SourceSchemes(listOf("file", "javascript")))
        started.clear()
        assertThat(permissive.launch(view("file:///sdcard/a.html"), all)).isEqualTo(LaunchResult.Failed)
        assertThat(started).isEmpty()
    }

    @Test
    fun ruleSchemesAndGeoAreAdmitted() {
        val spotify = view("spotify:search:tarte", "com.spotify.music", SourceLevel.APP_SEARCH)
        assertThat(launcher.launch(spotify, all)).isEqualTo(LaunchResult.Launched)
        val maps = view("geo:0,0?q=tarte", "com.google.android.apps.maps", SourceLevel.APP_SEARCH)
        assertThat(launcher.launch(maps, all)).isEqualTo(LaunchResult.Launched)
        assertThat(started.map { it.data?.scheme }).containsExactly("spotify", "geo").inOrder()
    }

    @Test
    fun theIntentIsCleanedBeforeItLeaves() {
        val tampered = Intent(Intent.ACTION_VIEW, Uri.parse("https://exemple.fr/a"))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            .apply { selector = Intent(Intent.ACTION_VIEW, Uri.parse("content://x/y")) }
        val ref = SourceRef(SourceLevel.EXACT_URL, tampered.toUri(Intent.URI_INTENT_SCHEME), null)
        assertThat(launcher.launch(ref, all)).isEqualTo(LaunchResult.Launched)
        val intent = started.single()
        assertThat(intent.selector).isNull()
        assertThat(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION).isEqualTo(0)
        assertThat(intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION).isEqualTo(0)
    }

    @Test
    fun aViewAimedAtAComponentIsRefused() {
        val aimed = Intent(Intent.ACTION_VIEW, Uri.parse("https://exemple.fr/a"))
            .setComponent(ComponentName(CHROME, "com.android.chrome.Internal"))
        val ref = SourceRef(SourceLevel.EXACT_URL, aimed.toUri(Intent.URI_INTENT_SCHEME), null)
        assertThat(launcher.launch(ref, all)).isEqualTo(LaunchResult.Failed)
    }

    @Test
    fun unavailableOrMalformedRefsOpenNothing() {
        assertThat(launcher.launch(SourceRef(SourceLevel.UNAVAILABLE, null, "Notes"), all))
            .isEqualTo(LaunchResult.Failed)
        assertThat(launcher.launch(SourceRef(SourceLevel.EXACT_URL, "intent:#Intent;%%;end", null), all))
            .isEqualTo(LaunchResult.Failed)
        assertThat(started).isEmpty()
    }
}
