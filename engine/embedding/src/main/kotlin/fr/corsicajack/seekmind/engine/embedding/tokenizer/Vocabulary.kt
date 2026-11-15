package fr.corsicajack.seekmind.engine.embedding.tokenizer

import java.io.IOException
import java.io.InputStream

/**
 * A Unigram vocabulary read from `vocab.bin` (docs/specs/embeddings.md §3.2), in compact structures:
 * the UTF-8 bytes of every token in one array, their offsets, their scores, and an open-addressing table
 * from a 64-bit hash of the token bytes to the token id. The bytes are compared on every hit.
 *
 * The special tokens (ids 0 to 3) are never looked up: a text that spells `<s>` is plain text.
 */
class Vocabulary private constructor(
    private val bytes: ByteArray,
    private val offsets: IntArray,
    private val scores: FloatArray,
    private val table: IntArray,
) {
    /** Number of tokens, special tokens included. */
    val size: Int get() = scores.size

    /** Length in bytes of the longest token. */
    val maxTokenBytes: Int

    /** Lowest score of the vocabulary, special tokens included. */
    val minScore: Float

    init {
        var longest = 0
        var lowest = Float.POSITIVE_INFINITY
        for (id in scores.indices) {
            longest = maxOf(longest, offsets[id + 1] - offsets[id])
            lowest = minOf(lowest, scores[id])
        }
        maxTokenBytes = longest
        minScore = lowest
    }

    /** Memory held by the structures, in bytes (array payloads; object headers excluded). */
    val retainedBytes: Long
        get() = bytes.size.toLong() + 4L * (offsets.size + scores.size + table.size)

    fun score(id: Int): Float = scores[id]

    /** The UTF-8 bytes of token [id]. */
    fun tokenBytes(id: Int): ByteArray = bytes.copyOfRange(offsets[id], offsets[id + 1])

    /** Id of the token whose bytes are `source[from until from + length]`, or -1. */
    fun idOf(source: ByteArray, from: Int, length: Int): Int = idOf(source, from, length, hashOf(source, from, length))

    /** Same as [idOf] with the [hash] of the bytes already computed by [hashStep]. */
    internal fun idOf(source: ByteArray, from: Int, length: Int, hash: Long): Int {
        val mask = table.size - 1
        var slot = slotOf(hash, mask)
        while (true) {
            val id = table[slot]
            if (id == EMPTY) return -1
            if (sameBytes(id, source, from, length)) return id
            slot = (slot + 1) and mask
        }
    }

    private fun sameBytes(id: Int, source: ByteArray, from: Int, length: Int): Boolean {
        val start = offsets[id]
        if (offsets[id + 1] - start != length) return false
        for (i in 0 until length) {
            if (bytes[start + i] != source[from + i]) return false
        }
        return true
    }

    companion object {
        const val BOS_ID = 0
        const val PAD_ID = 1
        const val EOS_ID = 2
        const val UNK_ID = 3

        private val SPECIAL_TOKENS = listOf("<s>", "<pad>", "</s>", "<unk>")
        private val MAGIC = byteArrayOf('S'.code.toByte(), 'M'.code.toByte(), 'V'.code.toByte(), '1'.code.toByte())
        private const val EMPTY = -1
        private const val FNV_OFFSET = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
        private const val FNV_PRIME = 0x100000001b3L

        /** Hash of zero bytes; extend it with [hashStep], one byte at a time (FNV-1a, 64 bits). */
        internal const val HASH_SEED: Long = FNV_OFFSET

        internal fun hashStep(hash: Long, byte: Byte): Long = (hash xor (byte.toLong() and 0xFF)) * FNV_PRIME

        internal fun hashOf(source: ByteArray, from: Int, length: Int): Long {
            var hash = HASH_SEED
            for (i in from until from + length) hash = hashStep(hash, source[i])
            return hash
        }

        private fun slotOf(hash: Long, mask: Int): Int = ((hash xor (hash ushr 32)).toInt()) and mask

        /** Reads a whole `vocab.bin` stream; the stream is not closed. */
        @Throws(IOException::class)
        fun read(input: InputStream): Vocabulary = parse(input.readBytes())

        /** Parses the content of a `vocab.bin` file. */
        @Throws(IOException::class)
        fun parse(blob: ByteArray): Vocabulary {
            val reader = LittleEndianReader(blob)
            if (!MAGIC.indices.all { reader.byte() == MAGIC[it] }) throw IOException("not a vocab.bin file (bad magic)")
            val count = reader.int32()
            if (count < SPECIAL_TOKENS.size || count > (blob.size - 8) / MIN_ENTRY_BYTES) {
                throw IOException("implausible token count: $count")
            }
            val payload = blob.size - 8 - count * (Short.SIZE_BYTES + Float.SIZE_BYTES)
            val bytes = ByteArray(payload)
            val offsets = IntArray(count + 1)
            val scores = FloatArray(count)
            var end = 0
            for (id in 0 until count) {
                val length = reader.uint16()
                if (length == 0 || end + length > payload) throw IOException("bad length of token $id: $length")
                reader.copyTo(bytes, end, length)
                end += length
                offsets[id + 1] = end
                scores[id] = Float.fromBits(reader.int32())
                if (scores[id].isNaN()) throw IOException("score of token $id is not a number")
            }
            if (!reader.atEnd() || end != payload) throw IOException("trailing bytes after $count tokens")
            for ((id, name) in SPECIAL_TOKENS.withIndex()) {
                if (!bytes.copyOfRange(offsets[id], offsets[id + 1]).contentEquals(name.toByteArray())) {
                    throw IOException("token $id is not $name")
                }
            }
            return Vocabulary(bytes, offsets, scores, buildTable(bytes, offsets, count))
        }

        /** Load factor at most 1/2; the first occurrence of a duplicated token wins. */
        private fun buildTable(bytes: ByteArray, offsets: IntArray, count: Int): IntArray {
            var capacity = Integer.highestOneBit(count) shl 2
            if (capacity < 16) capacity = 16
            val table = IntArray(capacity) { EMPTY }
            val mask = capacity - 1
            for (id in SPECIAL_TOKENS.size until count) {
                val start = offsets[id]
                val length = offsets[id + 1] - start
                var slot = slotOf(hashOf(bytes, start, length), mask)
                var duplicate = false
                while (table[slot] != EMPTY) {
                    val other = table[slot]
                    if (offsets[other + 1] - offsets[other] == length &&
                        (0 until length).all { bytes[offsets[other] + it] == bytes[start + it] }
                    ) {
                        duplicate = true
                        break
                    }
                    slot = (slot + 1) and mask
                }
                if (!duplicate) table[slot] = id
            }
            return table
        }

        /** uint16 length, at least one byte, float32 score. */
        private const val MIN_ENTRY_BYTES = Short.SIZE_BYTES + 1 + Float.SIZE_BYTES
    }
}

private class LittleEndianReader(private val blob: ByteArray) {
    private var position = 0

    fun atEnd(): Boolean = position == blob.size

    private fun require(count: Int) {
        if (blob.size - position < count) throw IOException("truncated vocab.bin at byte $position")
    }

    fun byte(): Byte {
        require(1)
        return blob[position++]
    }

    fun uint16(): Int {
        require(2)
        val value = (blob[position].toInt() and 0xFF) or ((blob[position + 1].toInt() and 0xFF) shl 8)
        position += 2
        return value
    }

    fun int32(): Int {
        require(4)
        var value = 0
        for (i in 3 downTo 0) value = (value shl 8) or (blob[position + i].toInt() and 0xFF)
        position += 4
        return value
    }

    fun copyTo(target: ByteArray, offset: Int, length: Int) {
        require(length)
        blob.copyInto(target, offset, position, position + length)
        position += length
    }
}
