package fr.corsicajack.seekmind.engine.search.query

/** A launchable application: its package and its label as the launcher shows it. */
data class AppEntry(val packageName: String, val label: String)

/**
 * The application filter of docs/specs/recherche.md §4 (ticket M5-03): a preposition followed by the
 * label or an alias of an installed application, the longest name first. Without a preposition nothing
 * is extracted ("chrome" may be a word of the query). Pure Kotlin: built by `AndroidAppCatalog` from
 * the installed applications.
 *
 * @param aliases alias → packages (`res/raw/search_app_aliases.json`); an alias counts only for the
 *     packages that are installed.
 */
class AppMatcher(apps: Collection<AppEntry>, aliases: Map<String, Set<String>> = emptyMap()) : AppExtractor {
    /** Comparison form of a name, as words → packages it designates. */
    private val names: Map<List<String>, Set<String>>
    private val longestName: Int

    init {
        val installed = apps.mapTo(HashSet()) { it.packageName }
        val byName = HashMap<List<String>, MutableSet<String>>()
        for (app in apps) {
            val words = Tokenizer.tokenize(app.label)
            if (words.isNotEmpty()) byName.getOrPut(words) { HashSet() } += app.packageName
        }
        for ((alias, packages) in aliases) {
            val words = Tokenizer.tokenize(alias)
            val present = packages.filter { it in installed }
            if (words.isNotEmpty() && present.isNotEmpty()) byName.getOrPut(words) { HashSet() } += present
        }
        names = byName
        longestName = byName.keys.maxOfOrNull { it.size } ?: 0
    }

    override fun extract(text: String): AppMatch? {
        if (names.isEmpty()) return null
        val words = words(text)
        var best: Triple<Int, Int, Set<String>>? = null // first word, last word (exclusive), packages
        var bestLength = 0
        for (start in words.indices) {
            val preposition = PREPOSITIONS.firstOrNull { matches(words, start, it) } ?: continue
            val nameStart = start + preposition.size
            for (length in minOf(longestName, words.size - nameStart) downTo 1) {
                if (length <= bestLength) break
                val key = words.subList(nameStart, nameStart + length).map { it.fold }
                val packages = names[key] ?: continue
                best = Triple(start, nameStart + length, packages)
                bestLength = length
                break
            }
        }
        val (first, end, packages) = best ?: return null
        val from = words[first].begin
        val to = words[end - 1].end
        return AppMatch(packages = packages, remaining = text.substring(0, from) + " " + text.substring(to))
    }

    private class Word(val begin: Int, val end: Int, val fold: String)

    private fun matches(words: List<Word>, at: Int, expected: List<String>): Boolean =
        at + expected.size <= words.size && expected.indices.all { words[at + it].fold == expected[it] }

    private companion object {
        /** docs/specs/recherche.md §4, in comparison form; "de l'appli" splits at the apostrophe. */
        val PREPOSITIONS: List<List<String>> = listOf(
            "de l'application", "de l'appli", "sur", "dans", "via", "depuis", "on", "in", "from",
        ).map { Tokenizer.tokenize(it) }

        fun words(text: String): List<Word> {
            val out = ArrayList<Word>()
            var i = 0
            while (i < text.length) {
                val codePoint = text.codePointAt(i)
                if (!Tokenizer.isWordChar(codePoint)) {
                    i += Character.charCount(codePoint)
                    continue
                }
                val begin = i
                while (i < text.length &&
                    Tokenizer.isWordChar(text.codePointAt(i))
                ) {
                    i += Character.charCount(text.codePointAt(i))
                }
                out += Word(begin, i, Tokenizer.fold(text.substring(begin, i)))
            }
            return out
        }
    }
}
