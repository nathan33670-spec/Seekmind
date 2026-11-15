package fr.corsicajack.seekmind.engine.embedding.catalog

import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.model.ModelSpec
import fr.corsicajack.seekmind.core.model.PackState
import fr.corsicajack.seekmind.engine.embedding.ExpectedFile
import fr.corsicajack.seekmind.engine.embedding.engine.BuiltInModels
import fr.corsicajack.seekmind.engine.embedding.engine.ModelFiles
import fr.corsicajack.seekmind.engine.embedding.engine.ModelSource
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

/** docs/specs/embeddings.md §5.1, §5.3, with an installer that stands for Google Play. */
class DefaultModelCatalogTest {
    private val directory = Files.createTempDirectory("multi").toFile()
    private val modelBytes = ByteArray(100) { it.toByte() }
    private val vocabularyBytes = ByteArray(10) { it.toByte() }
    private val digests = mapOf(
        PackLock.MODEL_FILE to ExpectedFile(sha256(modelBytes), modelBytes.size.toLong()),
        PackLock.VOCABULARY_FILE to ExpectedFile(sha256(vocabularyBytes), vocabularyBytes.size.toLong()),
    )

    /** NotInstalled → Downloading → Installed on install; the files appear on completion. */
    private inner class FakeInstaller : ModelPackInstaller {
        val states = MutableStateFlow<PackState>(PackState.NotInstalled)
        var installs = 0
        var removals = 0
        var failWith: Int? = null
        val history = mutableListOf<PackState>()

        override fun state(spec: ModelSpec): Flow<PackState> = states

        override suspend fun install(spec: ModelSpec): Flow<PackState> {
            installs++
            for (state in listOf(PackState.Downloading(0f), PackState.Downloading(0.5f))) {
                states.value = state
                history += state
            }
            val end = failWith?.let { PackState.Failed(it) } ?: PackState.Installed.also {
                File(directory, PackLock.MODEL_FILE).writeBytes(modelBytes)
                File(directory, PackLock.VOCABULARY_FILE).writeBytes(vocabularyBytes)
            }
            states.value = end
            history += end
            return states
        }

        override suspend fun remove(spec: ModelSpec) {
            removals++
            directory.listFiles()?.forEach { it.delete() }
            states.value = PackState.NotInstalled
        }

        override fun location(spec: ModelSpec): ModelFiles? {
            val model = File(directory, PackLock.MODEL_FILE)
            val vocabulary = File(directory, PackLock.VOCABULARY_FILE)
            return if (model.isFile && vocabulary.isFile) ModelFiles(model, vocabulary) else null
        }
    }

    private val installer = FakeInstaller()
    private val setting = object : ActiveModelSetting {
        var value = "fr"

        override suspend fun get() = value

        override suspend fun set(id: String) {
            value = id
        }
    }
    private val activated = mutableListOf<String>()

    private suspend fun catalog() =
        DefaultModelCatalog.create(installer, setting, Dispatchers.Unconfined, expected = { digests }) {
            activated +=
                it.id
        }

    @Test
    fun nothingIsDownloadedWithoutAnActionOfTheUser() = runTest {
        val catalog = catalog()
        assertThat(catalog.models.map { it.id }).containsExactly("fr", "multi")
        assertThat(catalog.state("multi").first()).isEqualTo(PackState.NotInstalled)
        assertThat(catalog.state("fr").first()).isEqualTo(PackState.Installed)
        assertThat(catalog.active.value).isEqualTo(BuiltInModels.FR)
        assertThat(installer.installs).isEqualTo(0)
    }

    @Test
    fun installThenActivateThenRemoveTheActivePack() = runTest {
        val catalog = catalog()
        catalog.install("multi")
        assertThat(installer.history).containsExactly(
            PackState.Downloading(0f),
            PackState.Downloading(0.5f),
            PackState.Installed,
        ).inOrder()
        catalog.activate("multi")
        assertThat(catalog.active.value).isEqualTo(BuiltInModels.MULTI)
        assertThat(setting.value).isEqualTo("multi")
        assertThat(activated).containsExactly("multi")
        assertThat((catalog.loadable().source as ModelSource.Pack).files.model.name).isEqualTo(PackLock.MODEL_FILE)

        catalog.remove("multi")
        assertThat(catalog.active.value).isEqualTo(BuiltInModels.FR)
        assertThat(setting.value).isEqualTo("fr")
        assertThat(activated).containsExactly("multi", "fr").inOrder()
        assertThat(catalog.loadable()).isEqualTo(BuiltInModels.bundledFr)
        assertThat(installer.removals).isEqualTo(1)
    }

    @Test
    fun aFailedInstallationLeavesFrActive() = runTest {
        installer.failWith = -6
        val catalog = catalog()
        catalog.install("multi")
        assertThat(installer.states.value).isEqualTo(PackState.Failed(-6))
        assertThrows(PackUnavailableException::class.java) {
            kotlinx.coroutines.runBlocking { catalog.activate("multi") }
        }
        assertThat(catalog.active.value).isEqualTo(BuiltInModels.FR)
        assertThat(activated).isEmpty()
    }

    @Test
    fun aPackThatNoLongerMatchesItsDigestsIsNotActivated() = runTest {
        val catalog = catalog()
        catalog.install("multi")
        File(directory, PackLock.MODEL_FILE).writeBytes(ByteArray(100))
        assertThrows(PackUnavailableException::class.java) {
            kotlinx.coroutines.runBlocking { catalog.activate("multi") }
        }
        assertThat(catalog.active.value).isEqualTo(BuiltInModels.FR)
    }

    @Test
    fun anActivePackThatDisappearedFallsBackToFr() = runTest {
        setting.value = "multi"
        val catalog = catalog()
        assertThat(catalog.active.value).isEqualTo(BuiltInModels.FR)
        assertThat(setting.value).isEqualTo("fr")
    }

    @Test
    fun playStatusesBecomePackStates() {
        fun state(status: PackStatus, done: Long = 0, total: Long = 0, error: Int = 0) =
            PlayModelPackInstaller.toState(PackProgress(status, done, total, error))
        assertThat(state(PackStatus.NOT_INSTALLED)).isEqualTo(PackState.NotInstalled)
        assertThat(state(PackStatus.DOWNLOADING, 25, 100)).isEqualTo(PackState.Downloading(0.25f))
        assertThat(state(PackStatus.WAITING_FOR_WIFI, 0, 100)).isEqualTo(PackState.Downloading(0f))
        assertThat(state(PackStatus.REQUIRES_USER_CONFIRMATION)).isEqualTo(PackState.Downloading(0f))
        assertThat(state(PackStatus.COMPLETED, 100, 100)).isEqualTo(PackState.Installed)
        assertThat(state(PackStatus.FAILED, error = -6)).isEqualTo(PackState.Failed(-6))
        assertThat(state(PackStatus.CANCELED, error = -7)).isEqualTo(PackState.Failed(-7))
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
        "%02x".format(it)
    }
}
