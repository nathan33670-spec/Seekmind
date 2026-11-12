package fr.corsicajack.seekmind.core.database.key

import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.common.Outcome
import fr.corsicajack.seekmind.core.common.SmLog
import java.io.File
import java.nio.file.Files
import java.security.GeneralSecurityException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.junit.After
import org.junit.Before
import org.junit.Test

class DbKeyStoreTest {
    private val directory: File = Files.createTempDirectory("dbkey").toFile()
    private val keyFile = File(directory, DbKeyStore.FILE_NAME)
    private val wrapper = SoftwareKeyWrapper()
    private val store = DbKeyStore(keyFile, wrapper)
    private val logged = mutableListOf<String>()
    private val previousSink = SmLog.sink
    private val previousLevel = SmLog.minLevel

    @Before
    fun captureLogs() {
        SmLog.minLevel = SmLog.Level.DEBUG
        SmLog.sink = SmLog.Sink { _, _, message -> logged += message }
    }

    @After
    fun cleanUp() {
        SmLog.sink = previousSink
        SmLog.minLevel = previousLevel
        directory.deleteRecursively()
    }

    @Test
    fun `two calls give the same key`() {
        val first = key(store.getOrCreateKey(databaseExists = false))
        val second = key(store.getOrCreateKey(databaseExists = true))
        assertThat(first).hasLength(32)
        assertThat(second).isEqualTo(first)
    }

    @Test
    fun `after destroy a new key is created`() {
        val first = key(store.getOrCreateKey(databaseExists = false))
        store.destroy()
        assertThat(keyFile.exists()).isFalse()
        assertThat(wrapper.exists()).isFalse()
        val second = key(store.getOrCreateKey(databaseExists = false))
        assertThat(second).isNotEqualTo(first)
    }

    @Test
    fun `a database without key file is a missing key, not a new key`() {
        assertThat(store.getOrCreateKey(databaseExists = true)).isEqualTo(Outcome.Failure(DbKeyError.KeyMissing))
        assertThat(keyFile.exists()).isFalse()
    }

    @Test
    fun `an altered key file is a typed error`() {
        store.getOrCreateKey(databaseExists = false)
        val bytes = keyFile.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x01).toByte()
        keyFile.writeBytes(bytes)
        assertThat(store.getOrCreateKey(databaseExists = true)).isEqualTo(Outcome.Failure(DbKeyError.FileCorrupt))
    }

    @Test
    fun `a truncated or unknown file is a typed error`() {
        store.getOrCreateKey(databaseExists = false)
        val bytes = keyFile.readBytes()
        keyFile.writeBytes(bytes.copyOf(bytes.size - 1))
        assertThat(store.getOrCreateKey(databaseExists = true)).isEqualTo(Outcome.Failure(DbKeyError.FileCorrupt))
        keyFile.writeBytes(bytes.copyOf().also { it[0] = 2 })
        assertThat(store.getOrCreateKey(databaseExists = true)).isEqualTo(Outcome.Failure(DbKeyError.FileCorrupt))
    }

    @Test
    fun `a lost keystore key is a typed error`() {
        store.getOrCreateKey(databaseExists = false)
        wrapper.delete()
        assertThat(store.getOrCreateKey(databaseExists = true)).isEqualTo(Outcome.Failure(DbKeyError.AliasInvalid))
    }

    @Test
    fun `the key in clear is neither written nor logged`() {
        val key = key(store.getOrCreateKey(databaseExists = false))
        val file = keyFile.readBytes()
        assertThat(file).hasLength(WrappedKey.SIZE)
        assertThat(file.toList().windowed(key.size).none { it == key.toList() }).isTrue()
        assertThat(directory.listFiles()!!.map { it.name }).containsExactly(DbKeyStore.FILE_NAME)
        assertThat(logged).isEmpty()
    }

    private fun key(outcome: Outcome<ByteArray, DbKeyError>): ByteArray =
        (outcome as? Outcome.Success)?.value ?: error("expected a key, got $outcome")

    /** AES-256-GCM in software: same contract as the Keystore wrapper, for the JVM. */
    private class SoftwareKeyWrapper : KeyWrapper {
        private var secret: SecretKey? = null

        override fun exists() = secret != null

        override fun create() {
            secret = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        }

        override fun wrap(key: ByteArray): WrappedKey {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secret) }
            return WrappedKey(cipher.iv, cipher.doFinal(key))
        }

        override fun unwrap(wrapped: WrappedKey): Outcome<ByteArray, DbKeyError> = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                secret ?: return Outcome.Failure(DbKeyError.AliasInvalid),
                GCMParameterSpec(128, wrapped.iv),
            )
            Outcome.Success(cipher.doFinal(wrapped.ciphertext))
        } catch (_: AEADBadTagException) {
            Outcome.Failure(DbKeyError.FileCorrupt)
        } catch (_: GeneralSecurityException) {
            Outcome.Failure(DbKeyError.AliasInvalid)
        }

        override fun delete() {
            secret = null
        }
    }
}
