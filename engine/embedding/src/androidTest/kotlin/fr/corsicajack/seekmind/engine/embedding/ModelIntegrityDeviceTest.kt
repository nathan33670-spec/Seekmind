package fr.corsicajack.seekmind.engine.embedding

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The bundled model is checked against its manifest on its first load (ticket M4-06). */
@RunWith(AndroidJUnit4::class)
class ModelIntegrityDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val copy = File(context.noBackupFilesDir, "model/fr")

    @Before
    fun setUp() {
        copy.deleteRecursively()
    }

    private fun loader(open: (String) -> InputStream) = OrtModelLoader(context, open)

    @Test
    fun theBundledModelPassesTheCheck() {
        val file = OrtModelLoader(context).copyAsset(FR_ASSET)
        assertThat(file.isFile).isTrue()
        OrtModelLoader(context).verifyAsset(FR_ASSET)
    }

    @Test
    fun anAlteredModelIsRefusedAndNothingIsKept() {
        val altered = loader { path ->
            val bytes = context.assets.open(path).use { it.readBytes() }
            if (path.endsWith("model.onnx")) bytes[bytes.size / 2] = (bytes[bytes.size / 2] + 1).toByte()
            ByteArrayInputStream(bytes)
        }
        assertThrows(CorruptModelException::class.java) { altered.copyAsset(FR_ASSET) }
        assertThat(copy.listFiles().orEmpty().map { it.name }).isEmpty()
        assertThrows(CorruptModelException::class.java) {
            altered.open(ModelLocation.Asset(FR_ASSET), intraOpThreads = 1)
        }
    }

    /** The failed check is remembered: a search does not read and hash the model again. */
    @Test
    fun aFailedCheckIsNotRepeated() {
        var modelReads = 0
        val altered = loader { path ->
            val bytes = context.assets.open(path).use { it.readBytes() }
            if (path.endsWith("model.onnx")) {
                modelReads++
                bytes[0] = (bytes[0] + 1).toByte()
            }
            ByteArrayInputStream(bytes)
        }
        assertThrows(CorruptModelException::class.java) { altered.copyAsset(FR_ASSET) }
        assertThrows(CorruptModelException::class.java) { altered.copyAsset(FR_ASSET) }
        assertThrows(CorruptModelException::class.java) { altered.verifyAsset(FR_ASSET) }
        assertThat(modelReads).isEqualTo(1)
    }

    @Test
    fun anAlteredVocabularyIsRefused() {
        val altered = loader { path ->
            if (path.endsWith("vocab.bin")) ByteArrayInputStream(ByteArray(10)) else context.assets.open(path)
        }
        assertThrows(CorruptModelException::class.java) { altered.copyAsset(FR_ASSET) }
    }

    @Test
    fun aMissingModelIsRefused() {
        val missing = loader { path ->
            if (path.endsWith("model.onnx")) throw FileNotFoundException(path) else context.assets.open(path)
        }
        assertThrows(CorruptModelException::class.java) {
            missing.open(ModelLocation.Asset(FR_ASSET), intraOpThreads = 1)
        }
    }

    @Test
    fun aCopyAlteredAfterTheCheckIsReplaced() {
        val loader = OrtModelLoader(context)
        val file = loader.copyAsset(FR_ASSET)
        file.writeBytes(ByteArray(16))
        assertThat(loader.copyAsset(FR_ASSET).length()).isGreaterThan(16L)
    }

    private companion object {
        const val FR_ASSET = "model/fr/model.onnx"
    }
}
