package fr.corsicajack.seekmind.engine.capture.url

import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.engine.capture.tree.FakeTree
import org.junit.Test

/** docs/specs/capture.md §5.2 steps 1 to 3 on fake trees (ticket M2-04). */
class UrlExtractorTest {
    private val profiles = BrowserProfiles(
        mapOf(BROWSER to BrowserProfile(urlBarId = "url_bar", pageTitleClass = "org.example.PageView")),
    )
    private val extractor = UrlExtractor(profiles)
    private val fakes = FakeTree()

    @Test
    fun `the address bar of a known browser is read and normalised`() {
        val root = browser { bar("fr.wikipedia.org/wiki/Corse") }
        assertThat(extractor.read(BROWSER, root)).isEqualTo(AddressBar.Read("https://fr.wikipedia.org/wiki/Corse"))
    }

    @Test
    fun `a focused address bar gives no URL`() {
        val root = browser { bar("recette pât", focused = true) }
        assertThat(extractor.read(BROWSER, root)).isEqualTo(AddressBar.Focused)
        // Even when the text typed so far looks like an address.
        val typing = browser { bar("example.com", focused = true) }
        assertThat(extractor.read(BROWSER, typing)).isEqualTo(AddressBar.Focused)
    }

    @Test
    fun `search terms in the bar give no URL`() {
        assertThat(extractor.read(BROWSER, browser { bar("recette pâtes truffe") })).isEqualTo(AddressBar.Rejected)
        assertThat(extractor.read(BROWSER, browser { bar(null) })).isEqualTo(AddressBar.Rejected)
    }

    @Test
    fun `a missing or invisible bar is hidden`() {
        assertThat(extractor.read(BROWSER, browser { child { text = "article" } })).isEqualTo(AddressBar.Hidden)
        assertThat(extractor.read(BROWSER, browser { bar("example.com", visible = false) }))
            .isEqualTo(AddressBar.Hidden)
    }

    @Test
    fun `the first visible bar wins`() {
        val root = browser {
            bar("hidden.example.com", visible = false)
            bar("shown.example.com")
            bar("second.example.com")
        }
        assertThat(extractor.read(BROWSER, root)).isEqualTo(AddressBar.Read("https://shown.example.com"))
    }

    @Test
    fun `a package without a profile is not read`() {
        val root = fakes.tree {
            child {
                viewId = "com.example.notes:id/url_bar"
                text = "example.com"
            }
        }
        assertThat(extractor.read("com.example.notes", root)).isEqualTo(AddressBar.NoProfile)
        assertThat(fakes.obtained).hasSize(1) // the root only: nothing was searched
    }

    @Test
    fun `the nodes found are released, the root is not`() {
        val root = browser {
            bar("one.example.com", visible = false)
            bar("two.example.com")
        }
        extractor.read(BROWSER, root)
        assertThat(fakes.unreleased).containsExactly(root)
    }

    private fun browser(build: FakeTree.Spec.() -> Unit) = fakes.tree {
        className = "android.widget.FrameLayout"
        build()
    }

    private fun FakeTree.Spec.bar(url: String?, focused: Boolean = false, visible: Boolean = true) = child {
        className = "android.widget.EditText"
        viewId = "$BROWSER:id/url_bar"
        editable = true
        text = url
        this.focused = focused
        this.visible = visible
    }

    private companion object {
        const val BROWSER = "com.example.browser"
    }
}
