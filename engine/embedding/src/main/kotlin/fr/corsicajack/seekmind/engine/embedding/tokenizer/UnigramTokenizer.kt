package fr.corsicajack.seekmind.engine.embedding.tokenizer

import fr.corsicajack.seekmind.engine.embedding.tokenizer.Vocabulary.Companion.BOS_ID
import fr.corsicajack.seekmind.engine.embedding.tokenizer.Vocabulary.Companion.EOS_ID
import fr.corsicajack.seekmind.engine.embedding.tokenizer.Vocabulary.Companion.UNK_ID

/**
 * SentencePiece Unigram tokenizer (docs/specs/embeddings.md §3.1), independent of the model: it uses
 * the [vocabulary] it is given. Stateless and safe to share between threads.
 */
class UnigramTokenizer(private val vocabulary: Vocabulary) : Tokenizer {
    /** Score of an unknown character, as in the reference implementation. */
    private val unknownScore: Double = vocabulary.minScore.toDouble() - UNKNOWN_PENALTY

    override fun encode(text: String, maxTokens: Int): Encoding {
        require(maxTokens >= 2) { "maxTokens must leave room for <s> and </s>: $maxTokens" }
        val budget = maxTokens - 2
        val tokens = IntList(minOf(budget, text.length + 1))
        if (budget > 0) {
            val utf8 = preTokenize(UnigramNormalizer.normalize(text))
            segmentWords(utf8, tokens, budget)
        }
        val count = minOf(tokens.size, budget)
        val ids = IntArray(count + 2)
        ids[0] = BOS_ID
        tokens.copyInto(ids, 1, count)
        ids[count + 1] = EOS_ID
        return Encoding(ids, IntArray(ids.size) { 1 })
    }

    /** Step 2: every space becomes `▁`, and `▁` is prepended; empty text gives no word. */
    private fun preTokenize(normalized: String): ByteArray {
        if (normalized.isEmpty()) return ByteArray(0)
        val marked = normalized.replace(' ', WORD_MARK)
        val withPrefix = if (marked[0] == WORD_MARK) marked else WORD_MARK + marked
        return withPrefix.toByteArray(Charsets.UTF_8)
    }

    /** Splits [utf8] before every `▁` and segments each word until [budget] tokens are collected. */
    private fun segmentWords(utf8: ByteArray, tokens: IntList, budget: Int) {
        val lattice = Lattice(utf8.size)
        var start = 0
        while (start < utf8.size && tokens.size < budget) {
            var end = start + MARK_BYTES
            while (end < utf8.size && !isMarkAt(utf8, end)) end++
            segment(utf8, start, end, lattice, tokens)
            start = end
        }
    }

    /**
     * Step 3: Viterbi over `utf8[from until to]`, the best path being the one with the highest sum of
     * scores. Same traversal order and strict comparisons as the reference implementation, so that ties
     * are broken the same way. A position without a one-character token gets an unknown node.
     */
    private fun segment(utf8: ByteArray, from: Int, to: Int, lattice: Lattice, tokens: IntList) {
        val length = to - from
        lattice.reset(length)
        var start = 0
        while (start < length) {
            val base = lattice.score[start]
            val charLength = charLength(utf8[from + start])
            var hasSingleChar = false
            var hash = Vocabulary.HASH_SEED
            val limit = minOf(length - start, vocabulary.maxTokenBytes)
            for (size in 1..limit) {
                hash = Vocabulary.hashStep(hash, utf8[from + start + size - 1])
                val atBoundary = start + size == length || !isContinuation(utf8[from + start + size])
                if (!atBoundary) continue
                val id = vocabulary.idOf(utf8, from + start, size, hash)
                if (id < 0) continue
                lattice.offer(start + size, start, id, base + vocabulary.score(id))
                if (size == charLength) hasSingleChar = true
            }
            if (!hasSingleChar) lattice.offer(start + charLength, start, UNK_ID, base + unknownScore)
            start += charLength
        }
        emit(utf8, from, lattice, length, tokens)
    }

    /** Walks the best path back; consecutive unknown nodes are fused, then looked up as one token. */
    private fun emit(utf8: ByteArray, from: Int, lattice: Lattice, length: Int, tokens: IntList) {
        val first = tokens.size
        var end = length
        var unknownEnd = -1
        while (end > 0) {
            val start = lattice.startsAt[end]
            val id = lattice.id[end]
            if (id == UNK_ID) {
                if (unknownEnd < 0) unknownEnd = end
            } else {
                if (unknownEnd >= 0) {
                    tokens.add(fusedUnknown(utf8, from, end, unknownEnd))
                    unknownEnd = -1
                }
                tokens.add(id)
            }
            end = start
        }
        if (unknownEnd >= 0) tokens.add(fusedUnknown(utf8, from, 0, unknownEnd))
        tokens.reverseFrom(first)
    }

    private fun fusedUnknown(utf8: ByteArray, from: Int, start: Int, end: Int): Int {
        val id = vocabulary.idOf(utf8, from + start, end - start)
        return if (id < 0) UNK_ID else id
    }

    /** Best path ending at every byte position of a word. */
    private class Lattice(capacity: Int) {
        val score = DoubleArray(capacity + 1)
        val startsAt = IntArray(capacity + 1)
        val id = IntArray(capacity + 1)

        fun reset(length: Int) {
            for (i in 0..length) {
                score[i] = 0.0
                startsAt[i] = NONE
                id[i] = NONE
            }
        }

        fun offer(end: Int, start: Int, token: Int, candidate: Double) {
            if (startsAt[end] == NONE || candidate > score[end]) {
                score[end] = candidate
                startsAt[end] = start
                id[end] = token
            }
        }
    }

    private class IntList(capacity: Int) {
        private var values = IntArray(maxOf(capacity, 8))
        var size = 0
            private set

        fun add(value: Int) {
            if (size == values.size) values = values.copyOf(size * 2)
            values[size++] = value
        }

        fun reverseFrom(from: Int) {
            var i = from
            var j = size - 1
            while (i < j) {
                val t = values[i]
                values[i++] = values[j]
                values[j--] = t
            }
        }

        fun copyInto(target: IntArray, offset: Int, count: Int) = values.copyInto(target, offset, 0, count)
    }

    private companion object {
        const val UNKNOWN_PENALTY = 10.0
        const val NONE = -1
        val WORD_MARK = Char(0x2581)

        /** `▁` in UTF-8: E2 96 81. */
        const val MARK_BYTES = 3
        const val MARK_0: Byte = 0xE2.toByte()
        const val MARK_1: Byte = 0x96.toByte()
        const val MARK_2: Byte = 0x81.toByte()

        fun isMarkAt(utf8: ByteArray, i: Int): Boolean =
            i + 2 < utf8.size && utf8[i] == MARK_0 && utf8[i + 1] == MARK_1 && utf8[i + 2] == MARK_2

        fun isContinuation(b: Byte): Boolean = (b.toInt() and 0xC0) == 0x80

        fun charLength(lead: Byte): Int {
            val b = lead.toInt() and 0xFF
            return when {
                b < 0x80 -> 1
                b < 0xE0 -> 2
                b < 0xF0 -> 3
                else -> 4
            }
        }
    }
}
