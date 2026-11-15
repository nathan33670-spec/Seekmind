package fr.corsicajack.seekmind.engine.source

import android.content.Intent
import android.net.Uri
import fr.corsicajack.seekmind.core.model.SourceLevel
import fr.corsicajack.seekmind.core.model.SourceRef

/** The http(s) links of a text (EF-SRC-05), offered in the detail of a capture and opened only on tap. */
object SourceLinks {
    const val MAX_LINKS = 5

    private val LINK = Regex("""https?://[^\s<>"'`]+""", RegexOption.IGNORE_CASE)
    private const val TRAILING = ".,;:!?)]}»…'\""

    /** At most [MAX_LINKS] distinct links, in the order of the text, without their trailing punctuation. */
    fun linksIn(text: String): List<String> = LINK.findAll(text)
        .map { it.value.trimEnd { c -> c in TRAILING } }
        .filter { link -> link.substringAfter("://").isNotEmpty() }
        .distinct()
        .take(MAX_LINKS)
        .toList()

    /**
     * The way to open a link the user tapped: the default browser, http(s) only (§5). Null for any other
     * scheme, which is then not opened.
     */
    fun refFor(link: String): SourceRef? {
        if (SourceSchemes.schemeOf(link) !in SourceSchemes.WEB) return null
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link)).addCategory(Intent.CATEGORY_BROWSABLE)
        return SourceRef(SourceLevel.EXACT_URL, intent.toUri(Intent.URI_INTENT_SCHEME), appLabel = null)
    }
}
