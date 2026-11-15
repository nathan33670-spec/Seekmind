package fr.corsicajack.seekmind.engine.embedding

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import org.junit.Assert.assertThrows
import org.junit.Test

class ModelIntegrityTest {
    private val bytes = ByteArray(200_000) { (it % 251).toByte() }
    private val expected = ExpectedFile(sha256(bytes), bytes.size.toLong())

    @Test
    fun aMatchingFileIsCopied() {
        val output = ByteArrayOutputStream()
        ModelIntegrity.copyChecked("model.onnx", ByteArrayInputStream(bytes), output, expected)
        assertThat(output.toByteArray()).isEqualTo(bytes)
    }

    @Test
    fun theDigestIsCaseInsensitive() {
        ModelIntegrity.copyChecked(
            "model.onnx",
            ByteArrayInputStream(bytes),
            null,
            expected.copy(sha256 = expected.sha256.uppercase()),
        )
    }

    @Test
    fun anAlteredByteIsRefused() {
        val altered = bytes.copyOf().also { it[123_456] = (it[123_456] + 1).toByte() }
        assertThrows(CorruptModelException::class.java) {
            ModelIntegrity.copyChecked("model.onnx", ByteArrayInputStream(altered), null, expected)
        }
    }

    @Test
    fun aTruncatedFileIsRefused() {
        assertThrows(CorruptModelException::class.java) {
            ModelIntegrity.copyChecked("model.onnx", ByteArrayInputStream(bytes.copyOf(1000)), null, expected)
        }
    }

    @Test
    fun aFileMissingFromTheManifestIsRefused() {
        assertThrows(CorruptModelException::class.java) {
            ModelIntegrity.copyChecked("model.onnx", ByteArrayInputStream(bytes), null, null)
        }
    }

    private fun sha256(data: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
}
