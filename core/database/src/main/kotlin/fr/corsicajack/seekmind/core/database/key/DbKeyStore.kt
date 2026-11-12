package fr.corsicajack.seekmind.core.database.key

import fr.corsicajack.seekmind.core.common.Outcome
import java.io.File
import java.io.IOException
import java.security.SecureRandom

/**
 * The 32-byte key of the SQLCipher database (docs/07-securite-vie-privee.md §4, docs/specs/stockage.md §2),
 * drawn from [SecureRandom], wrapped by the Keystore key of [wrapper] and kept wrapped in [keyFile].
 * The key in clear is never written nor logged; the caller wipes the returned array after use.
 */
class DbKeyStore(
    private val keyFile: File,
    private val wrapper: KeyWrapper,
    private val random: SecureRandom = SecureRandom(),
) {
    /**
     * The existing key, or a new one when there is neither key file nor database.
     * [databaseExists]: a database without key file means [DbKeyError.KeyMissing], never a new key.
     */
    @Synchronized
    fun getOrCreateKey(databaseExists: Boolean): Outcome<ByteArray, DbKeyError> {
        if (!keyFile.exists()) {
            return if (databaseExists) Outcome.Failure(DbKeyError.KeyMissing) else Outcome.Success(create())
        }
        val wrapped = readWrapped() ?: return Outcome.Failure(DbKeyError.FileCorrupt)
        if (!wrapper.exists()) return Outcome.Failure(DbKeyError.AliasInvalid)
        return when (val key = wrapper.unwrap(wrapped)) {
            is Outcome.Success -> if (key.value.size ==
                WrappedKey.KEY_BYTES
            ) {
                key
            } else {
                Outcome.Failure(DbKeyError.FileCorrupt)
            }

            is Outcome.Failure -> key
        }
    }

    /** Deletes the key file and the Keystore key; the next [getOrCreateKey] creates a new key. */
    @Synchronized
    fun destroy() {
        keyFile.delete()
        wrapper.delete()
    }

    private fun create(): ByteArray {
        val key = ByteArray(WrappedKey.KEY_BYTES).also(random::nextBytes)
        wrapper.create()
        val encoded = wrapper.wrap(key).encode()
        keyFile.parentFile?.mkdirs()
        val partial = File(keyFile.path + ".part")
        partial.writeBytes(encoded)
        if (!partial.renameTo(keyFile)) {
            partial.delete()
            throw IOException("cannot write the key file")
        }
        return key
    }

    private fun readWrapped(): WrappedKey? = try {
        WrappedKey.decode(keyFile.readBytes())
    } catch (_: IOException) {
        null
    }

    companion object {
        /** Name of the wrapped key in `noBackupFilesDir`. */
        const val FILE_NAME = "db.key"
    }
}
