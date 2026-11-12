package fr.corsicajack.seekmind.core.database.key

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WrappedKeyTest {
    private val iv = ByteArray(WrappedKey.IV_BYTES) { it.toByte() }
    private val ciphertext = ByteArray(WrappedKey.CIPHERTEXT_BYTES) { (100 + it).toByte() }

    @Test
    fun `version, IV, then ciphertext`() {
        val bytes = WrappedKey(iv, ciphertext).encode()
        assertThat(bytes).hasLength(61)
        assertThat(bytes[0]).isEqualTo(1.toByte())
        assertThat(bytes.copyOfRange(1, 13)).isEqualTo(iv)
        assertThat(bytes.copyOfRange(13, 61)).isEqualTo(ciphertext)
    }

    @Test
    fun `round trip`() {
        val decoded = WrappedKey.decode(WrappedKey(iv, ciphertext).encode())!!
        assertThat(decoded.iv).isEqualTo(iv)
        assertThat(decoded.ciphertext).isEqualTo(ciphertext)
    }

    @Test
    fun `other sizes and versions are rejected`() {
        val bytes = WrappedKey(iv, ciphertext).encode()
        assertThat(WrappedKey.decode(bytes.copyOf(60))).isNull()
        assertThat(WrappedKey.decode(bytes + 0)).isNull()
        assertThat(WrappedKey.decode(bytes.copyOf().also { it[0] = 0 })).isNull()
        assertThat(WrappedKey.decode(ByteArray(0))).isNull()
    }
}
