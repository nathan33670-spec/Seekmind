package fr.corsicajack.seekmind.engine.source

import android.net.Uri
import fr.corsicajack.seekmind.core.model.Capture

/**
 * The variables of the application rules (docs/specs/retour-source.md §4), taken from the content of a
 * capture, URL-encoded and bounded to [MAX_CHARS] characters before encoding. A blank value is missing.
 */
object RuleVariables {
    const val MAX_CHARS = 100
    const val TITLE = "title"
    const val HANDLE = "handle"
    const val FIRST_LINE = "firstLine"
    val NAMES: Set<String> = setOf(TITLE, HANDLE, FIRST_LINE)

    /** `@name`, not inside a word or an e-mail address (`a@b.c` is not a handle). */
    private val HANDLE_PATTERN = Regex("""(?<![A-Za-z0-9._@])@([A-Za-z0-9._]{2,30})(?![A-Za-z0-9._@])""")

    /** The raw value of [name] for [capture], or null when it is missing. */
    fun raw(name: String, capture: Capture): String? = when (name) {
        TITLE -> capture.windowTitle?.trim()?.takeIf { it.isNotEmpty() } ?: firstLine(capture.text)
        HANDLE -> HANDLE_PATTERN.find(capture.text)?.groupValues?.get(1)?.trimEnd('.')?.takeIf { it.length >= 2 }
        FIRST_LINE -> firstLine(capture.text)
        else -> null
    }

    /** The value of [name] as it goes into a URI, or null when it is missing. */
    fun encoded(name: String, capture: Capture): String? = raw(name, capture)?.let { Uri.encode(bounded(it)) }

    /** At most [MAX_CHARS] characters, without cutting a surrogate pair. */
    internal fun bounded(value: String): String {
        if (value.length <= MAX_CHARS) return value
        val end = if (Character.isHighSurrogate(value[MAX_CHARS - 1])) MAX_CHARS - 1 else MAX_CHARS
        return value.substring(0, end).trimEnd()
    }

    private fun firstLine(text: String): String? = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
}
