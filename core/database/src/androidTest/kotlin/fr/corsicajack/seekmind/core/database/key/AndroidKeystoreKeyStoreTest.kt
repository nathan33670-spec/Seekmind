package fr.corsicajack.seekmind.core.database.key

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.common.Outcome
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** DbKeyStore with the real Android Keystore (ticket M1-02), on an alias of its own. */
@RunWith(AndroidJUnit4::class)
class AndroidKeystoreKeyStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val keyFile = File(context.noBackupFilesDir, "test-" + DbKeyStore.FILE_NAME)
    private val wrapper = AndroidKeystoreWrapper(alias = "seekmind.db.kek.test")
    private val store = DbKeyStore(keyFile, wrapper)

    @Before
    @After
    fun clean() {
        store.destroy()
    }

    @Test
    fun twoCallsGiveTheSameKey() {
        val first = key(store.getOrCreateKey(databaseExists = false))
        assertThat(first).hasLength(32)
        assertThat(wrapper.exists()).isTrue()
        assertThat(
            key(DbKeyStore(keyFile, AndroidKeystoreWrapper("seekmind.db.kek.test")).getOrCreateKey(true)),
        ).isEqualTo(first)
    }

    @Test
    fun afterDestroyANewKeyIsCreated() {
        val first = key(store.getOrCreateKey(databaseExists = false))
        store.destroy()
        assertThat(keyFile.exists()).isFalse()
        assertThat(wrapper.exists()).isFalse()
        assertThat(key(store.getOrCreateKey(databaseExists = false))).isNotEqualTo(first)
    }

    @Test
    fun anAlteredKeyFileIsATypedError() {
        store.getOrCreateKey(databaseExists = false)
        val bytes = keyFile.readBytes()
        bytes[20] = (bytes[20].toInt() xor 0x40).toByte()
        keyFile.writeBytes(bytes)
        assertThat(store.getOrCreateKey(databaseExists = true)).isEqualTo(Outcome.Failure(DbKeyError.FileCorrupt))
    }

    @Test
    fun aDeletedKeystoreKeyIsATypedError() {
        store.getOrCreateKey(databaseExists = false)
        wrapper.delete()
        assertThat(store.getOrCreateKey(databaseExists = true)).isEqualTo(Outcome.Failure(DbKeyError.AliasInvalid))
    }

    @Test
    fun theKeyInClearIsNotInTheFile() {
        val key = key(store.getOrCreateKey(databaseExists = false)).toList()
        val file = keyFile.readBytes()
        assertThat(file).hasLength(WrappedKey.SIZE)
        assertThat(file.toList().windowed(key.size).none { it == key }).isTrue()
    }

    private fun key(outcome: Outcome<ByteArray, DbKeyError>): ByteArray =
        (outcome as? Outcome.Success)?.value ?: error("expected a key, got $outcome")
}
