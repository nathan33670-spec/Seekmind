package fr.corsicajack.seekmind.engine.search.lexical

import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.model.ParsedQuery
import fr.corsicajack.seekmind.engine.search.query.QueryParser
import fr.corsicajack.seekmind.engine.search.query.StopWords
import java.time.ZoneId
import org.junit.Test

class FtsExpressionTest {
    private val parser = QueryParser(StopWords(listOf("de", "la", "the")))

    private fun parse(query: String): ParsedQuery = parser.parse(query, now = 0L, zone = ZoneId.of("UTC"))

    private fun expression(query: String) = FtsExpression.of(parse(query))

    @Test
    fun `the example of the spec`() {
        assertThat(
            expression("resto italien pâtes truffe"),
        ).isEqualTo("\"resto\" OR \"italien\" OR \"pates\" OR \"truffe\"*")
    }

    @Test
    fun `the last word is a prefix from three characters`() {
        assertThat(expression("photo ch")).isEqualTo("\"photo\" OR \"ch\"")
        assertThat(expression("photo cha")).isEqualTo("\"photo\" OR \"cha\"*")
        assertThat(expression("été")).isEqualTo("\"ete\"*")
    }

    @Test
    fun `a code is searched as typed and as a prefix`() {
        assertThat(expression("code SUMMER20")).isEqualTo(
            "\"code\" OR \"summer20\"* OR \"SUMMER20\" OR \"SUMMER20\"*",
        )
        assertThat(expression("colis 1Z999AA10123456784 livré")).isEqualTo(
            "\"colis\" OR \"1z999aa10123456784\" OR \"livre\"* OR \"1Z999AA10123456784\" OR \"1Z999AA10123456784\"*",
        )
    }

    @Test
    fun `no word, no expression`() {
        assertThat(expression("")).isNull()
        assertThat(expression("  ... ?! ")).isNull()
    }

    /** Hostile input: every term is a quoted string, and nothing else than OR joins them. */
    @Test
    fun `no FTS5 syntax comes from the query`() {
        val hostile = listOf(
            "\"", "\"\"", "a\"b", "\" OR 1=1 --", "(truffe", "truffe)", "NEAR(a b, 2)", "a NEAR b", "*", "truffe*",
            "text:truffe", "{text title}: truffe", "^truffe", "a AND b", "a NOT b", "-truffe", "+truffe",
            "'; DROP TABLE capture; --", Char(0).toString(), "a${Char(2)}b${Char(3)}c", "\\\"", "x\"* OR \"y",
        )
        val term = Regex("\"(?:[^\"]|\"\")*\"\\*?")
        hostile.forEach { query ->
            val expression = expression(query) ?: return@forEach
            val terms = expression.split(" OR ")
            terms.forEach { assertThat(it).matches(term.pattern) }
            // Inside a term, every quote is doubled.
            terms.forEach {
                assertThat(it.trimEnd('*').removeSurrounding("\"").replace("\"\"", "")).doesNotContain("\"")
            }
        }
    }

    @Test
    fun `quotes inside a term are doubled`() {
        assertThat(FtsExpression.quote("a\"b")).isEqualTo("\"a\"\"b\"")
    }
}
