package fr.corsicajack.seekmind.engine.index

import fr.corsicajack.seekmind.core.model.config.IndexConfig

/** A fragment of a capture's text: `[start, end)` in that text, which is not duplicated (§5). */
data class ChunkSpan(val start: Int, val end: Int) {
    val length: Int get() = end - start
}

/**
 * Splits a capture's text into fragments for the semantic search (docs/specs/indexation.md §5): whole
 * lines grouped greedily up to [IndexConfig.chunkMaxChars]; a longer line is cut at its last sentence
 * end, else its last space, before the limit; at most [IndexConfig.maxChunksPerCapture] fragments.
 * Fragments are contiguous: each one keeps the line break that ends it, so that their slices put end to
 * end give back the text they cover.
 */
class Chunker(private val config: IndexConfig = IndexConfig()) {
    fun split(text: String): List<ChunkSpan> {
        val max = config.chunkMaxChars
        val spans = mutableListOf<ChunkSpan>()
        var start = 0
        var end = 0
        var position = 0
        while (position < text.length && spans.size < config.maxChunksPerCapture) {
            val lineEnd = text.indexOf('\n', position).let { if (it < 0) text.length else it + 1 }
            val lineLength = lineEnd - position
            when {
                // The line fits in the current fragment.
                end - start + lineLength <= max -> {
                    end = lineEnd
                    position = lineEnd
                }

                // The current fragment is full: close it, the line starts the next one.
                end > start -> {
                    spans += ChunkSpan(start, end)
                    start = end
                }

                // A line longer than a fragment: cut it.
                else -> {
                    val cut = cutPoint(text, position, max)
                    spans += ChunkSpan(position, cut)
                    position = cut
                    start = cut
                    end = cut
                }
            }
        }
        if (end > start && spans.size < config.maxChunksPerCapture) spans += ChunkSpan(start, end)
        return spans
    }

    /** End of the first piece of the long line at [from]: after a sentence end, else a space, else at [max]. */
    private fun cutPoint(text: String, from: Int, max: Int): Int {
        val limit = from + max
        var sentence = -1
        var space = -1
        for (i in from + 1 until limit) {
            val c = text[i]
            if (c == ' ') {
                space = i + 1
                val previous = text[i - 1]
                if (previous == '.' || previous == '!' || previous == '?' || previous == '…') sentence = i + 1
            }
        }
        return when {
            sentence > from -> sentence
            space > from -> space
            else -> limit
        }
    }
}
