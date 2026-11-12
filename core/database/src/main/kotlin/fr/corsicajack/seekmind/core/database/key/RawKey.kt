package fr.corsicajack.seekmind.core.database.key

/**
 * SQLCipher raw-key form `x'<64 hex digits>'` (docs/specs/stockage.md §2): the key is used as is, without
 * derivation. Built byte by byte, never as text, so that no immutable copy of the key exists; the caller
 * wipes the result after use.
 */
object RawKey {
    private val HEX = byteArrayOf(
        '0'.code.toByte(), '1'.code.toByte(), '2'.code.toByte(), '3'.code.toByte(),
        '4'.code.toByte(), '5'.code.toByte(), '6'.code.toByte(), '7'.code.toByte(),
        '8'.code.toByte(), '9'.code.toByte(), 'a'.code.toByte(), 'b'.code.toByte(),
        'c'.code.toByte(), 'd'.code.toByte(), 'e'.code.toByte(), 'f'.code.toByte(),
    )

    fun encode(key: ByteArray): ByteArray {
        val out = ByteArray(3 + key.size * 2)
        out[0] = 'x'.code.toByte()
        out[1] = '\''.code.toByte()
        for (i in key.indices) {
            val b = key[i].toInt() and 0xff
            out[2 + i * 2] = HEX[b ushr 4]
            out[3 + i * 2] = HEX[b and 0x0f]
        }
        out[out.size - 1] = '\''.code.toByte()
        return out
    }
}
