package fr.corsicajack.seekmind.engine.embedding

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** A file listed in a `MODEL_MANIFEST.json`: its SHA-256 (lower-case hexadecimal) and its size. */
data class ExpectedFile(val sha256: String, val size: Long)

/**
 * The model files do not match their manifest (docs/specs/embeddings.md §4): the semantic search is
 * unavailable, the keyword search is not affected. Not an `IOException`, so that the loader never falls
 * back to another way of opening a corrupt model.
 */
class CorruptModelException(message: String) : IllegalStateException(message)

/** Checks the files of a model against the digests of its manifest (ticket M4-06). */
object ModelIntegrity {
    private const val BUFFER_BYTES = 64 * 1024

    /**
     * Copies [input] to [output] (or only reads it when [output] is null) and throws
     * [CorruptModelException] when [name] does not match [expected].
     */
    fun copyChecked(name: String, input: InputStream, output: OutputStream?, expected: ExpectedFile?) {
        if (expected == null) throw CorruptModelException("$name is not listed in the manifest")
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_BYTES)
        var size = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
            output?.write(buffer, 0, read)
            size += read
        }
        if (size != expected.size) throw CorruptModelException("$name: $size bytes, ${expected.size} expected")
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != expected.sha256.lowercase()) throw CorruptModelException("$name: digest mismatch")
    }
}
