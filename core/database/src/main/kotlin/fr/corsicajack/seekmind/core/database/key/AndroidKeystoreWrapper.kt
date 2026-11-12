package fr.corsicajack.seekmind.core.database.key

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import fr.corsicajack.seekmind.core.common.Outcome
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Key-encryption key in the Android Keystore (docs/07-securite-vie-privee.md §4): AES-256-GCM, not
 * exportable, StrongBox when the device has one, otherwise the TEE. No user authentication: the
 * accessibility service writes as soon as the screen is on, and embeddings run while the phone is locked.
 */
class AndroidKeystoreWrapper(private val alias: String = ALIAS) : KeyWrapper {
    private val keyStore: KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    override fun exists(): Boolean = keyStore.containsAlias(alias)

    override fun create() {
        delete()
        try {
            generate(strongBox = true)
        } catch (_: StrongBoxUnavailableException) {
            generate(strongBox = false)
        }
    }

    override fun wrap(key: ByteArray): WrappedKey {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        return WrappedKey(cipher.iv, cipher.doFinal(key))
    }

    override fun unwrap(wrapped: WrappedKey): Outcome<ByteArray, DbKeyError> = try {
        val key = secretKey() ?: return Outcome.Failure(DbKeyError.AliasInvalid)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(WrappedKey.TAG_BYTES * 8, wrapped.iv))
        }
        Outcome.Success(cipher.doFinal(wrapped.ciphertext))
    } catch (_: AEADBadTagException) {
        Outcome.Failure(DbKeyError.FileCorrupt)
    } catch (_: KeyPermanentlyInvalidatedException) {
        Outcome.Failure(DbKeyError.AliasInvalid)
    } catch (_: UnrecoverableKeyException) {
        Outcome.Failure(DbKeyError.AliasInvalid)
    } catch (_: GeneralSecurityException) {
        Outcome.Failure(DbKeyError.AliasInvalid)
    }

    override fun delete() {
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
    }

    private fun secretKey(): SecretKey? = keyStore.getKey(alias, null) as? SecretKey

    private fun generate(strongBox: Boolean) {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(KEY_BITS)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(false)
            .setIsStrongBoxBacked(strongBox)
            .build()
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply { init(spec) }.generateKey()
    }

    companion object {
        /** docs/07-securite-vie-privee.md §4. A new alias version means a new key-encryption key. */
        const val ALIAS = "seekmind.db.kek.v1"
        private const val PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_BITS = 256
    }
}
