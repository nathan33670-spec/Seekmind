package fr.corsicajack.seekmind.engine.capture.url

import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.model.config.CaptureConfig
import fr.corsicajack.seekmind.core.testing.FakeClock
import org.junit.Test

/** docs/specs/capture.md §5.2 step 4 (ticket M2-04). */
class UrlTrackerTest {
    private val clock = FakeClock()
    private val tracker = UrlTracker(CaptureConfig(), clock)
    private val read = AddressBar.Read("https://example.com/article")

    @Test
    fun `a URL read is returned`() {
        assertThat(tracker.urlOf(BROWSER, read, "Article")).isEqualTo(read.url)
    }

    @Test
    fun `a hidden bar reuses the URL read on the same page`() {
        tracker.urlOf(BROWSER, read, "Article")
        clock.advance(9 * 60_000)
        assertThat(tracker.urlOf(BROWSER, AddressBar.Hidden, "Article")).isEqualTo(read.url)
    }

    @Test
    fun `the URL is not reused after ten minutes`() {
        tracker.urlOf(BROWSER, read, "Article")
        clock.advance(10 * 60_000)
        assertThat(tracker.urlOf(BROWSER, AddressBar.Hidden, "Article")).isNull()
    }

    @Test
    fun `the URL is not reused on another page, in another browser, or without a title`() {
        tracker.urlOf(BROWSER, read, "Article")
        assertThat(tracker.urlOf(BROWSER, AddressBar.Hidden, "Autre article")).isNull()
        assertThat(tracker.urlOf(BROWSER, AddressBar.Hidden, null)).isNull()
        assertThat(tracker.urlOf("org.example.other", AddressBar.Hidden, "Article")).isNull()
    }

    @Test
    fun `a URL read without a page title is not remembered and forgets the previous one`() {
        tracker.urlOf(BROWSER, read, "Article")
        assertThat(tracker.urlOf(BROWSER, AddressBar.Read("https://example.com/b"), null))
            .isEqualTo("https://example.com/b")
        assertThat(tracker.urlOf(BROWSER, AddressBar.Hidden, "Article")).isNull()
    }

    @Test
    fun `a focused or rejected bar gives no URL, even on the remembered page`() {
        tracker.urlOf(BROWSER, read, "Article")
        assertThat(tracker.urlOf(BROWSER, AddressBar.Focused, "Article")).isNull()
        assertThat(tracker.urlOf(BROWSER, AddressBar.Rejected, "Article")).isNull()
        assertThat(tracker.urlOf(BROWSER, AddressBar.NoProfile, "Article")).isNull()
    }

    @Test
    fun `clear forgets every URL`() {
        tracker.urlOf(BROWSER, read, "Article")
        tracker.clear()
        assertThat(tracker.urlOf(BROWSER, AddressBar.Hidden, "Article")).isNull()
    }

    private companion object {
        const val BROWSER = "com.example.browser"
    }
}
