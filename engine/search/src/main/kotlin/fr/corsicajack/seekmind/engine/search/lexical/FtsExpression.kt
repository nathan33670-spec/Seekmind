package fr.corsicajack.seekmind.engine.search.lexical

import fr.corsicajack.seekmind.core.model.ParsedQuery

/**
 * The FTS5 expression of a query (docs/specs/recherche.md §5). Every term is a quoted string, internal
 * quotes doubled: no FTS5 syntax (`NEAR`, `*`, `:`, `^`, parentheses, column filters, `AND`/`NOT`) can
 * come from the query. Terms are joined by `OR`; the last word becomes a prefix from 3 characters; a
 * code-shaped token is added as typed and as a prefix.
 */
object FtsExpression {
    /** Minimum length of the last word to search it as a prefix. */
    const val PREFIX_MIN_LENGTH = 3

    fun of(query: ParsedQuery): String? {
        val terms = LinkedHashSet<String>()
        query.tokens.forEachIndexed { i, token ->
            val prefix = i == query.tokens.lastIndex && token.codePointCount(0, token.length) >= PREFIX_MIN_LENGTH
            terms += quote(token) + if (prefix) "*" else ""
        }
        for (code in query.codeTokens) {
            terms += quote(code)
            terms += quote(code) + "*"
        }
        return terms.takeIf { it.isNotEmpty() }?.joinToString(" OR ")
    }

    fun quote(term: String): String = "\"" + term.replace("\"", "\"\"") + "\""
}
