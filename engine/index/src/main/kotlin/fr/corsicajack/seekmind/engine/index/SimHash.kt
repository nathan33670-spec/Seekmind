package fr.corsicajack.seekmind.engine.index

import java.text.Normalizer

/**
 * 64-bit SimHash of a text (docs/specs/indexation.md §3): words without case or diacritics, features
 * made of three consecutive words, FNV-1a 64 on each feature. Deterministic, pure Kotlin.
 */
object SimHash {
    fun of(text: String): Long {
        val words = words(text)
        val features = if (words.size <
            3
        ) {
            words
        } else {
            (0..words.size - 3).map { "${words[it]} ${words[it + 1]} ${words[it + 2]}" }
        }
        if (features.isEmpty()) return 0L
        val votes = IntArray(64)
        for (feature in features) {
            val hash = fnv1a64(feature.toByteArray(Charsets.UTF_8))
            for (bit in 0 until 64) votes[bit] += if (hash ushr bit and 1L == 1L) 1 else -1
        }
        var fingerprint = 0L
        for (bit in 0 until 64) if (votes[bit] > 0) fingerprint = fingerprint or (1L shl bit)
        return fingerprint
    }

    fun hamming(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    /** Runs of Unicode letters or digits, lower-cased, without diacritics. */
    fun words(text: String): List<String> {
        val folded = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .filterNot { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }
        return WORD.findAll(folded).map { it.value }.toList()
    }

    private val WORD = Regex("[\\p{L}\\p{N}]+")

    private const val FNV_OFFSET = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
    private const val FNV_PRIME = 0x100000001b3L

    fun fnv1a64(bytes: ByteArray): Long {
        var hash = FNV_OFFSET
        for (b in bytes) {
            hash = hash xor (b.toLong() and 0xff)
            hash *= FNV_PRIME
        }
        return hash
    }
}
