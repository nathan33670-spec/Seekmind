package fr.corsicajack.seekmind.engine.capture.url

/**
 * Turns the text of an address bar into a web URL, or rejects it (docs/specs/capture.md §5.2 step 3).
 * Rejected: search terms (inner whitespace, or no dot), schemes other than http and https, a missing
 * or malformed host or port (IPv6 literals included: they have no dot), control characters, more than [MAX_LENGTH] characters. Text without a
 * scheme gets `https://`. Scheme and host are lower-cased, user information (`user:password@`) is
 * dropped, path, query and fragment are kept as displayed.
 *
 * Written without android.net.Uri, so that it runs in JVM tests, and stricter than it: Uri parses any
 * string, the host check is done here.
 */
object UrlNormalizer {
    const val MAX_LENGTH = 2_048

    fun normalize(raw: CharSequence): String? {
        val text = raw.trim().toString()
        if (text.isEmpty() || text.any { it.isWhitespace() || it.isISOControl() }) return null
        if ('.' !in text) return null

        val separator = text.indexOf("://")
        val scheme: String
        val rest: String
        when {
            separator > 0 -> {
                scheme = text.substring(0, separator).lowercase()
                rest = text.substring(separator + 3)
            }

            OPAQUE_SCHEME.containsMatchIn(text) -> return null

            // mailto:, data:, intent:…
            else -> {
                scheme = "https"
                rest = text
            }
        }
        if (scheme != "http" && scheme != "https") return null

        val authorityEnd = rest.indexOfAny(charArrayOf('/', '?', '#')).let { if (it < 0) rest.length else it }
        val hostAndPort = rest.substring(0, authorityEnd).substringAfterLast('@')
        val (host, port) = splitPort(hostAndPort) ?: return null
        val validHost = validHost(host) ?: return null

        return "$scheme://$validHost$port${rest.substring(authorityEnd)}".takeIf { it.length <= MAX_LENGTH }
    }

    /** `host[:port]` into the host and `:port` (empty without a port); null for a malformed port. */
    private fun splitPort(hostAndPort: String): Pair<String, String>? {
        val colon = hostAndPort.lastIndexOf(':')
        if (colon < 0) return hostAndPort to ""
        val port = hostAndPort.substring(colon)
        return if (PORT.matches(port)) hostAndPort.substring(0, colon) to port else null
    }

    private fun validHost(host: String): String? = when {
        host.isEmpty() || host.startsWith('.') || host.endsWith('.') || ".." in host -> null
        '.' !in host -> null
        !host.all { it.isLetterOrDigit() || it == '-' || it == '.' } -> null
        else -> host
    }?.lowercase()

    /** `scheme:` not followed by a port number: an opaque URI such as `mailto:a@b.c`. */
    private val OPAQUE_SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:(?![0-9]{1,5}(?:[/?#]|$))")
    private val PORT = Regex("^:[0-9]{1,5}$")
}
