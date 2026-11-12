package fr.corsicajack.seekmind.engine.capture.url

import fr.corsicajack.seekmind.core.common.Clock
import fr.corsicajack.seekmind.core.model.config.CaptureConfig

/**
 * The URL of a capture (docs/specs/capture.md §5.2 step 4). A browser hides its address bar while the
 * page scrolls: the last URL read is remembered per package with the page title it was read with, and
 * reused while the bar is hidden, the title is the same, and the URL was read less than
 * [CaptureConfig.urlMemoryMs] ago. Memory only, one entry per browser; capture thread only.
 */
class UrlTracker(private val config: CaptureConfig, private val clock: Clock) {
    private class Memory(val pageTitle: String, val url: String, val readAt: Long)

    private val memories = HashMap<String, Memory>()

    fun urlOf(packageName: String, bar: AddressBar, pageTitle: String?): String? = when (bar) {
        is AddressBar.Read -> {
            if (pageTitle != null) {
                memories[packageName] = Memory(pageTitle, bar.url, clock.elapsedRealtime())
            } else {
                memories.remove(packageName)
            }
            bar.url
        }

        AddressBar.Hidden -> memories[packageName]
            ?.takeIf { pageTitle != null && it.pageTitle == pageTitle }
            ?.takeIf { clock.elapsedRealtime() - it.readAt < config.urlMemoryMs }
            ?.url

        AddressBar.Focused, AddressBar.Rejected, AddressBar.NoProfile -> null
    }

    /** Forgets every URL: pause, end of consent, wipe. */
    fun clear() = memories.clear()
}
