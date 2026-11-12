package fr.corsicajack.seekmind.core.database.key

import java.nio.ByteBuffer

/**
 * Content of `noBackupFilesDir/db.key` (docs/07-securite-vie-privee.md §4): version, IV, ciphertext.
 *
 * Layout, version 1: one byte `1`, 12 bytes of IV, then 48 bytes of AES-256-GCM ciphertext (the
 * 32-byte database key and its 16-byte authentication tag). Any other content is rejected.
 */
class WrappedKey(val iv: ByteArray, val ciphertext: ByteArray) {
    init {
        require(iv.size == IV_BYTES) { "IV of ${iv.size} bytes" }
        require(ciphertext.size == CIPHERTEXT_BYTES) { "ciphertext of ${ciphertext.size} bytes" }
    }

    fun encode(): ByteArray = ByteBuffer.allocate(SIZE).put(VERSION).put(iv).put(ciphertext).array()

    companion object {
        const val VERSION: Byte = 1
        const val KEY_BYTES = 32
        const val IV_BYTES = 12
        const val TAG_BYTES = 16
        const val CIPHERTEXT_BYTES = KEY_BYTES + TAG_BYTES
        const val SIZE = 1 + IV_BYTES + CIPHERTEXT_BYTES

        /** Null when [bytes] is not a version-1 key file. */
        fun decode(bytes: ByteArray): WrappedKey? {
            if (bytes.size != SIZE || bytes[0] != VERSION) return null
            return WrappedKey(bytes.copyOfRange(1, 1 + IV_BYTES), bytes.copyOfRange(1 + IV_BYTES, SIZE))
        }
    }
}
