package fr.corsicajack.seekmind.engine.search.query

import android.content.res.Resources
import fr.corsicajack.seekmind.engine.search.R

/**
 * French and English stop words (`res/raw/search_stopwords_fr.txt`, `res/raw/search_stopwords_en.txt`),
 * kept in their comparison form ([Tokenizer.fold]).
 */
class StopWords(words: Collection<String>) {
    private val folded: Set<String> = words.mapTo(HashSet()) { Tokenizer.fold(it) }

    val size: Int get() = folded.size

    /** [word] must already be a token, i.e. folded. */
    operator fun contains(word: String): Boolean = word in folded

    companion object {
        /** No stop word at all. */
        val EMPTY = StopWords(emptyList())

        fun load(resources: Resources): StopWords = StopWords(
            listOf(R.raw.search_stopwords_fr, R.raw.search_stopwords_en).flatMap { id ->
                parse(resources.openRawResource(id).bufferedReader(Charsets.UTF_8).use { it.readText() })
            },
        )

        /** One word per line; blank lines and lines starting with `#` are ignored. */
        fun parse(text: String): List<String> = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList()
    }
}
