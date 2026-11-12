package fr.corsicajack.seekmind.engine.capture.url

import fr.corsicajack.seekmind.engine.capture.tree.UiNode

/** What the address bar of the screen gave (docs/specs/capture.md §5.2). */
sealed interface AddressBar {
    /** The package is not a known browser. */
    data object NoProfile : AddressBar

    /** No visible address bar: scrolled away, or full screen. */
    data object Hidden : AddressBar

    /** The user is typing in the bar: what it shows is not the page's URL. */
    data object Focused : AddressBar

    /** The bar shows something that is not a web URL (search terms, another scheme…). */
    data object Rejected : AddressBar

    data class Read(val url: String) : AddressBar
}

/**
 * Reads a browser's address bar (docs/specs/capture.md §5.2 steps 1 to 3). The bar is an editable
 * field, read directly here and nowhere else. Called before TreeExtractor.extract, on the root the
 * caller still owns; the nodes found are released.
 */
class UrlExtractor(private val profiles: BrowserProfiles) {
    fun read(packageName: String, root: UiNode): AddressBar {
        val profile = profiles.profileFor(packageName) ?: return AddressBar.NoProfile
        val nodes = root.findByViewId("$packageName:id/${profile.urlBarId}")
        try {
            val bar = nodes.firstOrNull { it.isVisibleToUser } ?: return AddressBar.Hidden
            if (bar.isFocused) return AddressBar.Focused
            val url = bar.text?.let(UrlNormalizer::normalize) ?: return AddressBar.Rejected
            return AddressBar.Read(url)
        } finally {
            nodes.forEach(UiNode::release)
        }
    }
}
