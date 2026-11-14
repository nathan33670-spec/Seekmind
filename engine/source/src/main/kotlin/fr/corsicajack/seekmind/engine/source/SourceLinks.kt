package fr.corsicajack.seekmind.engine.source

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
}
