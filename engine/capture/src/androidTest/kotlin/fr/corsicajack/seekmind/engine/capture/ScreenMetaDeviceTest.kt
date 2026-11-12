package fr.corsicajack.seekmind.engine.capture

import android.app.UiAutomation
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.common.Clock
import fr.corsicajack.seekmind.core.common.Outcome
import fr.corsicajack.seekmind.core.model.config.CaptureConfig
import fr.corsicajack.seekmind.engine.capture.meta.PackageManagerActivityLookup
import fr.corsicajack.seekmind.engine.capture.prototype.ProbeAccessibilityService
import fr.corsicajack.seekmind.engine.capture.tree.A11yUiNode
import fr.corsicajack.seekmind.engine.capture.tree.ActiveWindow
import fr.corsicajack.seekmind.engine.capture.tree.TreeExtractor
import fr.corsicajack.seekmind.engine.capture.url.AddressBar
import fr.corsicajack.seekmind.engine.capture.url.BrowserProfile
import fr.corsicajack.seekmind.engine.capture.url.BrowserProfiles
import fr.corsicajack.seekmind.engine.capture.url.UrlExtractor
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Screen metadata and URL on a real accessibility tree (ticket M2-04, docs/specs/capture.md §5): the
 * browser table resource, the activity lookup, and E2E-13 at the level of this module (the address
 * bar of the fixture's WebActivity is read). Storing the URL with the capture is checked end to end
 * once the pipeline exists (M2-08).
 */
@RunWith(AndroidJUnit4::class)
class ScreenMetaDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    // Must be obtained before any other UiAutomation, otherwise accessibility services are suppressed.
    private val uiAutomation: UiAutomation =
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private val context = instrumentation.targetContext

    @Before
    fun enableService() {
        if (ProbeAccessibilityService.instance == null) {
            val component = "${context.packageName}/${ProbeAccessibilityService::class.java.name}"
            shell("settings put secure enabled_accessibility_services $component")
            shell("settings put secure accessibility_enabled 1")
        }
        check(poll { ProbeAccessibilityService.instance != null }) { "service not connected" }
    }

    @Test
    fun theBrowserTableParsesAndListsTheSpecifiedBrowsers() {
        val profiles = BrowserProfiles.load(context)
        assertThat(profiles.packages).containsAtLeast(
            "com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.brave.browser", "com.microsoft.emmx",
            "com.vivaldi.browser", "com.kiwibrowser.browser", "org.mozilla.firefox", "org.mozilla.fenix",
            "org.mozilla.focus", "com.sec.android.app.sbrowser", "com.opera.browser", "com.opera.mini.native",
            "com.duckduckgo.mobile.android",
        )
        assertThat(profiles.profileFor("com.android.chrome")?.urlBarId).isEqualTo("url_bar")
        assertThat(profiles.profileFor("org.mozilla.firefox")?.urlBarId).isEqualTo("mozac_browser_toolbar_url_view")
        assertThat(profiles.profileFor("com.sec.android.app.sbrowser")?.urlBarId).isEqualTo("location_bar_edit_text")
        assertThat(profiles.profileFor("com.opera.browser")?.urlBarId).isEqualTo("url_field")
        assertThat(profiles.profileFor("com.duckduckgo.mobile.android")?.urlBarId).isEqualTo("omnibarTextInput")
        assertThat(profiles.pageTitleClasses).isNotEmpty()
    }

    @Test
    fun anActivityIsRecognisedAndAViewClassIsNot() {
        val lookup = PackageManagerActivityLookup(context.packageManager)
        assertThat(lookup.isActivity(FIXTURE, "$FIXTURE.WebActivity")).isTrue()
        assertThat(lookup.isActivity(FIXTURE, "android.widget.LinearLayout")).isFalse()
        assertThat(lookup.isActivity(FIXTURE, "$FIXTURE.Missing")).isFalse()
    }

    /** E2E-13, at the level of this module. */
    @Test
    fun theAddressBarOfWebActivityIsRead() {
        val defaults = BrowserProfiles.load(context)
        val chrome = checkNotNull(defaults.profileFor("com.android.chrome"))
        val profiles = defaults.with(FIXTURE, BrowserProfile("url_bar", chrome.pageTitleClass))
        val extractor = UrlExtractor(profiles)

        shell("am start -W -S -n $FIXTURE/$FIXTURE.WebActivity")
        var bar: AddressBar = AddressBar.Hidden
        var window: ActiveWindow? = null
        val shown = poll {
            window?.root?.release()
            window =
                (A11yUiNode.activeWindow(service()) as? Outcome.Success)?.value?.takeIf { it.packageName == FIXTURE }
            bar = window?.let { extractor.read(FIXTURE, it.root) } ?: AddressBar.Hidden
            bar is AddressBar.Read
        }
        val active = checkNotNull(window) { "WebActivity not on screen" }
        try {
            assertThat(shown).isTrue()
            assertThat(bar).isEqualTo(AddressBar.Read("https://fixture.seekmind.test/article/42"))

            // The bar is an editable field: its text is not a line of the capture.
            val tree = TreeExtractor(CaptureConfig(), SystemClockClock, profiles.pageTitleClasses).extract(active.root)
            val extraction = (tree as Outcome.Success).value
            assertThat(extraction.lines.map { it.text }).doesNotContain("fixture.seekmind.test/article/42")
            Log.i(TAG, "m2_04_page_title_found=${if (extraction.pageTitle != null) 1 else 0}")
        } finally {
            active.root.release()
        }
    }

    private fun service() = checkNotNull(ProbeAccessibilityService.instance) { "service not connected" }

    /** Runs a shell command and waits for its end, by reading its output. */
    private fun shell(command: String) {
        uiAutomation.executeShellCommand(command).use { descriptor ->
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
    }

    private fun poll(condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return true
            SystemClock.sleep(POLL_MS)
        }
        return condition()
    }

    private object SystemClockClock : Clock {
        override fun nowMillis() = System.currentTimeMillis()

        override fun elapsedRealtime() = SystemClock.elapsedRealtime()
    }

    private companion object {
        const val FIXTURE = "fr.corsicajack.seekmind.fixture"
        const val TAG = "SeekMindProto"
        const val TIMEOUT_MS = 20_000L
        const val POLL_MS = 200L
    }
}
