package fr.corsicajack.seekmind.engine.source

import java.util.Locale

/**
 * Schemes an Intent built by the return to the source may carry (docs/specs/retour-source.md §5): http,
 * https, geo, and those of the application rules. `file`, `content`, `javascript` and `intent` are never
 * admitted, even if a rule names them.
 */
class SourceSchemes(ruleSchemes: Collection<String> = emptyList()) {
    private val allowed: Set<String> =
        (BUILT_IN + ruleSchemes.map(::normalise)).filterNot { it in FORBIDDEN || it.isEmpty() }.toSet()

    fun isAllowed(scheme: String?): Boolean = scheme != null && normalise(scheme) in allowed

    companion object {
        val WEB: Set<String> = setOf("http", "https")
        private val BUILT_IN = WEB + "geo"
        private val FORBIDDEN = setOf("file", "content", "javascript", "intent", "android-app", "data")

        /** The scheme of [uri] in lower case, or null if it has none. */
        fun schemeOf(uri: String): String? {
            val colon = uri.indexOf(':')
            if (colon <= 0) return null
            val scheme = uri.substring(0, colon)
            val valid = scheme.first().isLetter() && scheme.all { it.isLetterOrDigit() || it in "+-." }
            return if (valid) normalise(scheme) else null
        }

        private fun normalise(scheme: String) = scheme.trim().lowercase(Locale.ROOT)
    }
}
