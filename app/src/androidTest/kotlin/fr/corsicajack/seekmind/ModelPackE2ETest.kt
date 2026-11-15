package fr.corsicajack.seekmind

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.common.SystemClockJvm
import fr.corsicajack.seekmind.core.database.store.NewChunk
import fr.corsicajack.seekmind.core.model.Delivery
import fr.corsicajack.seekmind.core.model.ModelSpec
import fr.corsicajack.seekmind.core.model.SearchFilters
import fr.corsicajack.seekmind.core.model.config.EmbeddingConfig
import fr.corsicajack.seekmind.core.model.port.GovernorState
import fr.corsicajack.seekmind.core.model.port.ThermalGovernor
import fr.corsicajack.seekmind.core.testing.Models
import fr.corsicajack.seekmind.core.testing.TemporaryDatabase
import fr.corsicajack.seekmind.engine.embedding.catalog.ActiveModelSetting
import fr.corsicajack.seekmind.engine.embedding.catalog.DefaultModelCatalog
import fr.corsicajack.seekmind.engine.embedding.catalog.FileModelPackInstaller
import fr.corsicajack.seekmind.engine.embedding.catalog.ImportResult
import fr.corsicajack.seekmind.engine.embedding.catalog.PackLock
import fr.corsicajack.seekmind.engine.embedding.codec.VectorCodec
import fr.corsicajack.seekmind.engine.embedding.engine.BuiltInModels
import fr.corsicajack.seekmind.engine.embedding.engine.OrtEmbeddingEngineFactory
import fr.corsicajack.seekmind.engine.embedding.engine.SharedEmbeddingEngineProvider
import fr.corsicajack.seekmind.engine.index.embedding.EmbeddingBatchRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * E2E-21 (docs/06-qualite-tests-ci.md §4, ticket M4-07): a test pack, the files of `fr` under another
 * model id, assembled at run time from the assets (no model is committed), imported, then activated.
 * The keyword search answers while the vectors are computed again; the new vectors carry the new id.
 */
@RunWith(AndroidJUnit4::class)
class ModelPackE2ETest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val database = TemporaryDatabase(context, "e2e_21")
    private val packs = File(context.cacheDir.parentFile, "no_backup/test_packs")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val config = EmbeddingConfig()
    private val testPack = ModelSpec("test", modelId = 99, setOf("fr"), BuiltInModels.FR.sizeBytes, Delivery.PACK, null)
    private val governor = object : ThermalGovernor {
        override val state = MutableStateFlow(GovernorState(true, true, 2, null))
    }

    @After
    fun tearDown() {
        scope.cancel()
        packs.deleteRecursively()
        database.delete()
    }

    /** A `.smpack` archive with the bundled `fr` files: entries stored, as tools/model/pack_model.py writes them. */
    private fun archive(): ByteArrayInputStream {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            for (name in listOf(PackLock.MODEL_FILE, PackLock.VOCABULARY_FILE, PackLock.MANIFEST_FILE)) {
                zip.putNextEntry(ZipEntry(name))
                context.assets.open("model/fr/$name").use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return ByteArrayInputStream(bytes.toByteArray())
    }

    @Test
    fun theTestPackIsActivatedAndTheVectorsAreComputedAgain() = runBlocking<Unit> {
        val handle = database.open()
        val text = "Le restaurant italien de la rue Paradis sert des pâtes à la truffe le mardi soir."
        val id = handle.captures.insert(Models.newCapture(text, at = Models.AT), listOf(NewChunk(0, 0, text.length)))

        val installer = FileModelPackInstaller(packs, { if (it.id == "test") PackLock.FR else null }, Dispatchers.IO)
        assertThat(installer.import(testPack, archive())).isEqualTo(ImportResult.Imported)

        lateinit var engines: SharedEmbeddingEngineProvider
        val setting = object : ActiveModelSetting {
            var value = "fr"

            override suspend fun get() = value

            override suspend fun set(id: String) {
                value = id
            }
        }
        val catalog = DefaultModelCatalog.create(
            installer,
            setting,
            Dispatchers.IO,
            models = BuiltInModels.all + testPack,
            expected = { if (it.id == "test") PackLock.FR else PackLock.byModel[it.id] },
        ) { spec ->
            engines.release()
            handle.vectors.resetForModel(spec.modelId)
            handle.vectors.setInt8Scale(config.int8Scale)
        }
        engines = SharedEmbeddingEngineProvider(
            factory = OrtEmbeddingEngineFactory(context, config, Dispatchers.IO, Dispatchers.Default),
            activeModel = { catalog.loadable() },
            threads = { config.threadsDefault },
            config = config,
            scope = scope,
        )
        val runner =
            EmbeddingBatchRunner(handle.vectors, engines, VectorCodec(config.int8Scale), governor, SystemClockJvm)

        runner.run()
        assertThat(handle.vectors.modelId()).isEqualTo(BuiltInModels.FR.modelId)

        catalog.activate("test")
        assertThat(catalog.active.value).isEqualTo(testPack)
        // Recomputing: no vector yet, the keyword search answers.
        assertThat(handle.vectors.pendingCount()).isEqualTo(1)
        assertThat(handle.search.lexical("\"truffe\"", SearchFilters(), 10).map { it.captureId }).containsExactly(id)

        runner.run()
        assertThat(handle.vectors.pendingCount()).isEqualTo(0)
        assertThat(handle.vectors.modelId()).isEqualTo(99)
        assertThat(engines.use { it.modelId }).isEqualTo(99)
    }
}
