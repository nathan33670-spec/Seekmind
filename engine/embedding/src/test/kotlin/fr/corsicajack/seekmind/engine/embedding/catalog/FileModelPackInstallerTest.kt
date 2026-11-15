package fr.corsicajack.seekmind.engine.embedding.catalog

import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.model.Delivery
import fr.corsicajack.seekmind.core.model.ModelSpec
import fr.corsicajack.seekmind.core.model.PackState
import fr.corsicajack.seekmind.engine.embedding.ExpectedFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** docs/specs/embeddings.md §5.2: a `.smpack` archive is checked before anything is copied. */
class FileModelPackInstallerTest {
    private val root = Files.createTempDirectory("packs").toFile()
    private val spec = ModelSpec("test", 99, setOf("fr"), 20, Delivery.PACK, null)
    private val model = ByteArray(4_000) { (it % 7).toByte() }
    private val vocabulary = ByteArray(1_000) { (it % 5).toByte() }
    private val digests = mapOf(
        PackLock.MODEL_FILE to ExpectedFile(sha256(model), model.size.toLong()),
        PackLock.VOCABULARY_FILE to ExpectedFile(sha256(vocabulary), vocabulary.size.toLong()),
    )
    private val installer = FileModelPackInstaller(root, {
        if (it.id ==
            "test"
        ) {
            digests
        } else {
            null
        }
    }, Dispatchers.Unconfined)

    private fun archive(vararg entries: Pair<String, ByteArray>): ByteArrayInputStream {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return ByteArrayInputStream(bytes.toByteArray())
    }

    private val manifest = PackLock.MANIFEST_FILE to "{}".toByteArray()

    @Test
    fun aValidArchiveIsImported() = runTest {
        assertThat(installer.state(spec).first()).isEqualTo(PackState.NotInstalled)
        val result = installer.import(
            spec,
            archive(PackLock.MODEL_FILE to model, PackLock.VOCABULARY_FILE to vocabulary, manifest),
        )
        assertThat(result).isEqualTo(ImportResult.Imported)
        assertThat(installer.state(spec).first()).isEqualTo(PackState.Installed)
        assertThat(installer.location(spec)!!.model.readBytes()).isEqualTo(model)
        assertThat(root.list()!!.toList()).containsExactly("test")
    }

    @Test
    fun aWrongDigestIsRefusedAndNothingIsCopied() = runTest {
        val altered = model.copyOf().also { it[10] = 99 }
        val result = installer.import(
            spec,
            archive(
                PackLock.MODEL_FILE to altered,
                PackLock.VOCABULARY_FILE to vocabulary,
                manifest,
            ),
        )
        assertThat(result).isInstanceOf(ImportResult.Refused::class.java)
        assertThat(installer.location(spec)).isNull()
        assertThat(root.list()!!.toList()).isEmpty()
    }

    @Test
    fun anIncompleteArchiveIsRefused() = runTest {
        val result = installer.import(spec, archive(PackLock.MODEL_FILE to model, manifest))
        assertThat(result).isInstanceOf(ImportResult.Refused::class.java)
        assertThat(root.list()!!.toList()).isEmpty()
    }

    @Test
    fun anUnexpectedOrEscapingEntryIsRefused() = runTest {
        for (name in listOf("../evil", "model/model.onnx", "extra.txt")) {
            val result = installer.import(
                spec,
                archive(
                    PackLock.MODEL_FILE to model,
                    PackLock.VOCABULARY_FILE to vocabulary,
                    manifest,
                    name to byteArrayOf(1),
                ),
            )
            assertThat(result).isInstanceOf(ImportResult.Refused::class.java)
        }
        assertThat(root.list()!!.toList()).isEmpty()
        assertThat(root.parentFile!!.resolve("evil").exists()).isFalse()
    }

    @Test
    fun aModelWithoutDigestsIsRefused() = runTest {
        val other = spec.copy(id = "other")
        assertThat(installer.import(other, archive(manifest))).isInstanceOf(ImportResult.Refused::class.java)
    }

    @Test
    fun removalDeletesThePack() = runTest {
        installer.import(spec, archive(PackLock.MODEL_FILE to model, PackLock.VOCABULARY_FILE to vocabulary, manifest))
        installer.remove(spec)
        assertThat(installer.location(spec)).isNull()
        assertThat(installer.state(spec).first()).isEqualTo(PackState.NotInstalled)
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
        "%02x".format(it)
    }
}
