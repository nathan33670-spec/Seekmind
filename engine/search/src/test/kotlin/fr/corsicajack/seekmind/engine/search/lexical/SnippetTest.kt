package fr.corsicajack.seekmind.engine.search.lexical

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SnippetTest {
    private val open = Char(2)
    private val close = Char(3)

    @Test
    fun `the markers become inclusive ranges`() {
        val snippet = LexicalSearcher.snippet("…au ${open}restaurant$close des ${open}pâtes$close à la…")
        assertThat(snippet.text).isEqualTo("…au restaurant des pâtes à la…")
        assertThat(snippet.highlights.map { snippet.text.substring(it.first, it.last + 1) })
            .containsExactly("restaurant", "pâtes").inOrder()
    }

    @Test
    fun `a marker left open closes at the end, an empty one is dropped`() {
        val snippet = LexicalSearcher.snippet("fin ${open}truf")
        assertThat(snippet.text).isEqualTo("fin truf")
        assertThat(snippet.highlights).containsExactly(4..7)
        assertThat(LexicalSearcher.snippet("a$open${close}b").highlights).isEmpty()
        assertThat(LexicalSearcher.snippet("sans marque").highlights).isEmpty()
    }
}
