package fr.corsicajack.seekmind.core.database.key

import fr.corsicajack.seekmind.core.common.Outcome

/** The key-encryption key: AES-256-GCM in the Android Keystore in production, a fake in JVM tests. */
interface KeyWrapper {
    fun exists(): Boolean

    /** Creates the key-encryption key, replacing any previous one. */
    fun create()

    fun wrap(key: ByteArray): WrappedKey

    /** [DbKeyError.FileCorrupt] when the tag is rejected, [DbKeyError.AliasInvalid] when the key is unusable. */
    fun unwrap(wrapped: WrappedKey): Outcome<ByteArray, DbKeyError>

    fun delete()
}
