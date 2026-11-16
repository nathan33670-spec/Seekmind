package fr.corsicajack.seekmind.engine.search.query

import fr.corsicajack.seekmind.core.model.ParsedQuery
import fr.corsicajack.seekmind.core.model.QueryProfile
import fr.corsicajack.seekmind.core.model.SearchRequest
import java.text.Normalizer
import java.time.ZoneId

/**
 * Turns the text typed by the user into a [ParsedQuery] (docs/specs/recherche.md §2). Pure Kotlin:
 * the stop words and the extractors are given at construction.
 */
class QueryParser(private val stopWords: StopWords, private val temporal: TemporalExtractor = TemporalExtractor.NONE) {
    /**
     * Parses [raw]. [apps] recognises the application filter; it is built from the installed
     * applications, hence given at each call.
     */
    fun parse(raw: String, now: Long, zone: ZoneId, apps: AppExtractor = AppExtractor.NONE): ParsedQuery {
        var text = clean(raw)

        val time = temporal.extract(text, now, zone)
        if (time != null) text = collapseSpaces(time.remaining)

        val app = apps.extract(text)
        if (app != null) text = collapseSpaces(app.remaining)

        val words = Tokenizer.tokenize(text).distinct()
        val useful = words.filterNot { it in stopWords }
        val tokens = useful.ifEmpty { words }
        val codeTokens = codeTokens(text)

        return ParsedQuery(
            text = text,
            tokens = tokens,
            codeTokens = codeTokens,
            range = time?.range,
            rangeLabel = time?.label,
            packages = app?.packages.orEmpty(),
            profile = profileOf(tokens, codeTokens),
        )
    }

    /**
     * Parses the query of [request]; the filters chosen in the interface take precedence over those
     * found in the text: an explicit range replaces the extracted one (and its label), explicit packages
     * replace the extracted ones. The expressions are removed from the text in every case.
     */
    fun parse(request: SearchRequest, apps: AppExtractor = AppExtractor.NONE): ParsedQuery {
        val parsed = parse(request.rawQuery, request.now, request.zone, apps)
        val explicit = request.filters
        return parsed.copy(
            range = explicit.range ?: parsed.range,
            rangeLabel = if (explicit.range != null) null else parsed.rangeLabel,
            packages = explicit.packages.ifEmpty { parsed.packages },
        )
    }

    companion object {
        /** Minimum length of a code-shaped token. */
        const val CODE_MIN_LENGTH = 5

        /** Minimum number of useful words of a [QueryProfile.NATURAL] query. */
        const val NATURAL_MIN_WORDS = 5

        private val APOSTROPHES = intArrayOf(0x2018, 0x2019, 0x201A, 0x201B, 0x02BC, 0x2032, 0x00B4, 0x0060)
        private val QUOTES = intArrayOf(0x201C, 0x201D, 0x201E, 0x201F, 0x00AB, 0x00BB, 0x2033)
        private val CODE_CHARS = Regex("[\\p{L}\\p{N}_-]+")

        /** Step 1: NFC, typographic quotes and apostrophes made plain, spaces collapsed. */
        fun clean(raw: String): String {
            val nfc = Normalizer.normalize(raw, Normalizer.Form.NFC)
            val out = StringBuilder(nfc.length)
            var i = 0
            while (i < nfc.length) {
                val codePoint = nfc.codePointAt(i)
                when {
                    codePoint in APOSTROPHES -> out.append('\'')
                    codePoint in QUOTES -> out.append('"')
                    else -> out.appendCodePoint(codePoint)
                }
                i += Character.charCount(codePoint)
            }
            return collapseSpaces(out.toString())
        }

        /** Runs of whitespace (including no-break spaces) become one space; the ends are trimmed. */
        fun collapseSpaces(text: String): String {
            val out = StringBuilder(text.length)
            var pendingSpace = false
            var i = 0
            while (i < text.length) {
                val codePoint = text.codePointAt(i)
                if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
                    pendingSpace = out.isNotEmpty()
                } else {
                    if (pendingSpace) out.append(' ')
                    pendingSpace = false
                    out.appendCodePoint(codePoint)
                }
                i += Character.charCount(codePoint)
            }
            return out.toString()
        }

        /**
         * Step 6: words of at least [CODE_MIN_LENGTH] characters made of letters, digits, hyphens or
         * underscores, with at least one digit. Surrounding punctuation is ignored; the case is kept.
         */
        fun codeTokens(text: String): List<String> = text.split(' ')
            .map { word -> word.trim { !it.isLetterOrDigit() } }
            .filter { isCodeShaped(it) }
            .distinct()

        fun isCodeShaped(word: String): Boolean = word.length >= CODE_MIN_LENGTH &&
            CODE_CHARS.matches(word) &&
            word.any { it.isDigit() }

        private fun profileOf(tokens: List<String>, codeTokens: List<String>): QueryProfile = when {
            codeTokens.isNotEmpty() -> QueryProfile.CODE
            tokens.size >= NATURAL_MIN_WORDS -> QueryProfile.NATURAL
            else -> QueryProfile.DEFAULT
        }
    }
}
