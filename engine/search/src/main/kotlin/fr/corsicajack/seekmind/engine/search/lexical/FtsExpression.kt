package fr.corsicajack.seekmind.engine.search.lexical

import fr.corsicajack.seekmind.core.model.ParsedQuery

/**
 * The FTS5 expressions of a query (docs/specs/recherche.md §5). Every term is a quoted string, internal
 * quotes doubled: no FTS5 syntax (`NEAR`, `*`, `:`, `^`, parentheses, column filters, `AND`/`NOT`) can
 * come from the query; the only operators are those written here. The last word becomes a prefix from
 * 3 characters; a code-shaped token is added as typed and as a prefix, as an alternative.
 *
 * - [allOf]: every word (`AND`), or one of the codes. Searched first.
 * - [of]: any word or code (`OR`). Searched when [allOf] finds too little.
 */
object FtsExpression {
    /** Minimum length of the last word to search it as a prefix. */
    const val PREFIX_MIN_LENGTH = 3

    /** Any word or code: `"a" OR "b"*`. Null when the query has no word. */
    fun of(query: ParsedQuery): String? =
        (words(query) + codes(query)).takeIf { it.isNotEmpty() }?.distinct()?.joinToString(" OR ")

    /**
     * Every word, or one of the codes: `("a" AND "b"*) OR "CODE" OR "CODE"*`. Equal to [of] when the
     * query has a single word and no code. Null when the query has no word.
     */
    fun allOf(query: ParsedQuery): String? {
        val words = words(query)
        val alternatives = buildList {
            when (words.size) {
                0 -> Unit
                1 -> add(words.single())
                else -> add(words.joinToString(" AND ", "(", ")"))
            }
            addAll(codes(query))
        }
        return alternatives.takeIf { it.isNotEmpty() }?.distinct()?.joinToString(" OR ")
    }

    private fun words(query: ParsedQuery): List<String> {
        val terms = LinkedHashSet<String>()
        query.tokens.forEachIndexed { i, token ->
            val prefix = i == query.tokens.lastIndex && token.codePointCount(0, token.length) >= PREFIX_MIN_LENGTH
            terms += quote(token) + if (prefix) "*" else ""
        }
        return terms.toList()
    }

    private fun codes(query: ParsedQuery): List<String> = query.codeTokens.flatMap {
        listOf(quote(it), quote(it) + "*")
    }

    fun quote(term: String): String = "\"" + term.replace("\"", "\"\"") + "\""
}
