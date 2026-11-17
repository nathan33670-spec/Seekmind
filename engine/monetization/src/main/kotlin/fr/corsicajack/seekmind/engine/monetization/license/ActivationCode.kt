package fr.corsicajack.seekmind.engine.monetization.license

/**
 * The code inside what the user brings (docs/specs/monetisation.md §5.4, §5.5): a pasted code, a pasted
 * activation link, or the `seekmind://activate?t=<code>` link opened by the system. Shape only: the
 * code itself is checked by [LicenseActivator].
 */
object ActivationCode {
    const val LINK_PREFIX = "seekmind://activate?t="

    /** Longest accepted input: a code of [TokenVerifier.MAX_LENGTH] plus the longest prefix of a link. */
    private const val MAX_INPUT = TokenVerifier.MAX_LENGTH + 512
    private val CODE = Regex("^[A-Za-z0-9_.-]+$")

    /**
     * The code of a link received by `LinkActivity`, or null when the link is anything else: another
     * host, another parameter, an empty, oversized or ill-formed code.
     */
    fun fromLink(data: String?): String? {
        if (data == null || data.length > LINK_PREFIX.length + TokenVerifier.MAX_LENGTH) return null
        if (!data.startsWith(LINK_PREFIX)) return null
        return data.substring(LINK_PREFIX.length).takeIf { CODE.matches(it) }
    }

    /**
     * What to activate from a pasted text: the code of a pasted link (`seekmind://` or the relay page
     * `https://<site>/a#<code>`), or the text itself, trimmed. Null when nothing is left.
     */
    fun fromInput(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_INPUT) return trimmed.ifEmpty { null }
        fromLink(trimmed)?.let { return it }
        if (trimmed.startsWith("https://") && '#' in trimmed) {
            return trimmed.substringAfter('#').trim().ifEmpty { null }
        }
        return trimmed
    }
}
