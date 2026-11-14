package fr.corsicajack.seekmind.engine.embedding.engine

import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import fr.corsicajack.seekmind.core.model.config.EmbeddingConfig
import fr.corsicajack.seekmind.core.model.port.EmbeddingEngine
import java.io.File
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [OrtEmbeddingEngine] with the real models (docs/specs/embeddings.md §10): reference vectors, prefixes,
 * idle closing, and the measurements of §8, logged under the `SeekMindBench` tag.
 */
@RunWith(AndroidJUnit4::class)
class OrtEmbeddingEngineDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val config = EmbeddingConfig()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val factory = OrtEmbeddingEngineFactory(context, config, Dispatchers.IO, Dispatchers.Default)

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun frMatchesTheReferenceVectorsFromText() = runBlocking {
        val golden = golden("fr")
        factory.open(BuiltInModels.bundledFr, config.threadsDefault).use { engine ->
            assertThat(engine.modelId).isEqualTo(1)
            assertThat(engine.dimensions).isEqualTo(384)
            var lowest = 1.0
            for (item in golden) {
                val vector = when (item.kind) {
                    "query" -> engine.embedQuery(item.text)
                    else -> engine.embedPassages(listOf(item.text)).single()
                }
                assertThat(norm(vector)).isWithin(1e-4).of(1.0)
                val cosine = dot(vector, item.vector) / norm(item.vector)
                assertWithMessage(
                    "cosine for a ${item.kind} of ${item.text.length} chars",
                ).that(cosine).isAtLeast(MIN_COSINE)
                lowest = minOf(lowest, cosine)
            }
            measure("engine_fr_golden_lowest_cosine", lowest)
        }
    }

    @Test
    fun passagesInOneBatchMatchTheReferenceVectorsToo() = runBlocking {
        val passages = golden("fr").filter { it.kind == "passage" }
        factory.open(BuiltInModels.bundledFr, config.threadsDefault).use { engine ->
            val vectors = engine.embedPassages(passages.map { it.text })
            var lowest = 1.0
            passages.forEachIndexed { i, item ->
                lowest =
                    minOf(lowest, dot(vectors[i], item.vector) / norm(item.vector))
            }
            measure("engine_fr_golden_batched_lowest_cosine", lowest)
            assertThat(lowest).isAtLeast(MIN_COSINE)
        }
    }

    @Test
    fun theEngineAppliesThePrefixes() = runBlocking {
        factory.open(BuiltInModels.bundledFr, config.threadsDefault).use { engine ->
            val text = "horaires d'ouverture de la piscine municipale"
            val query = engine.embedQuery(text)
            val passage = engine.embedPassages(listOf(text)).single()
            val cosine = dot(query, passage)
            assertThat(cosine).isLessThan(0.999)
            assertThat(cosine).isGreaterThan(0.5)
        }
    }

    @Test
    fun theSessionClosesWhenIdleAndReloadsOnTheNextCall() = runBlocking {
        val provider = provider(EmbeddingConfig(idleCloseMs = 300))
        var first: EmbeddingEngine? = null
        val before = provider.use { engine ->
            first = engine
            engine.embedQuery("musée")
        }
        withTimeout(5_000) { while (provider.isLoaded()) delay(50) }
        assertThat((first as OrtEmbeddingEngine).isOpen).isFalse()
        val after = provider.use { engine ->
            assertThat(engine).isNotSameInstanceAs(first)
            engine.embedQuery("musée")
        }
        assertThat(after.toList()).isEqualTo(before.toList())
    }

    @Test
    fun memoryPressureClosesTheSession() = runBlocking {
        val provider = provider(config)
        provider.use { it.embedQuery("musée") }
        @Suppress("DEPRECATION")
        MemoryPressureRelease(provider).onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        withTimeout(5_000) { while (provider.isLoaded()) delay(50) }
        MemoryPressureRelease(provider).onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        provider.use { it.embedQuery("musée") }
        delay(200)
        assertThat(provider.isLoaded()).isTrue()
    }

    /** Spec §8: loading, a query, a passage of 256 tokens with 2 threads, process memory. */
    @Test
    fun measuresFr() = runBlocking {
        File(context.noBackupFilesDir, "model/fr").deleteRecursively()
        measureModel("fr", BuiltInModels.bundledFr)
    }

    @Test
    fun measuresMulti() = runBlocking {
        measureModel("multi", LoadableModel(BuiltInModels.MULTI, ModelSource.Pack(multiFiles())))
    }

    private suspend fun measureModel(label: String, model: LoadableModel) {
        System.gc()
        val pssBefore = pssKb()
        var start = SystemClock.elapsedRealtime()
        val engine = factory.open(model, config.threadsDefault)
        measure("engine_${label}_load_first_ms", (SystemClock.elapsedRealtime() - start).toDouble())
        engine.use {
            it.embedQuery("horaires du musée") // the first run allocates the arenas
            measure("engine_${label}_pss_delta_mb", (pssKb() - pssBefore) / 1024.0)
            measure("engine_${label}_pss_total_mb", pssKb() / 1024.0)
            measure("engine_${label}_query_ms_median", median { it.embedQuery("horaires du musée d'Orsay") })
            val passage = LONG_PASSAGE
            measure("engine_${label}_passage256_t2_ms_median", median { it.embedPassages(listOf(passage)) })
            measure(
                "engine_${label}_batch8x256_t2_ms_median",
                median(runs = 3) {
                    it.embedPassages(List(8) { passage })
                },
            )
        }
        start = SystemClock.elapsedRealtime()
        factory.open(model, config.threadsDefault).close()
        measure("engine_${label}_load_warm_ms", (SystemClock.elapsedRealtime() - start).toDouble())
    }

    private suspend fun median(runs: Int = RUNS, work: suspend () -> Unit): Double {
        repeat(WARMUP) { work() }
        val times = DoubleArray(runs) {
            val start = SystemClock.elapsedRealtimeNanos()
            work()
            (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
        }
        times.sort()
        return times[runs / 2]
    }

    private fun provider(config: EmbeddingConfig) = SharedEmbeddingEngineProvider(
        factory = OrtEmbeddingEngineFactory(context, config, Dispatchers.IO, Dispatchers.Default),
        activeModel = { BuiltInModels.bundledFr },
        threads = { config.threadsDefault },
        config = config,
        scope = scope,
    )

    private class GoldenItem(val kind: String, val text: String, val vector: FloatArray)

    private fun golden(model: String): List<GoldenItem> {
        val json =
            JSONObject(instrumentation.context.assets.open("embedding_golden_$model.json").bufferedReader().readText())
        val items = json.getJSONArray("items")
        return List(items.length()) { i ->
            val item = items.getJSONObject(i)
            val vector = item.getJSONArray("vector")
            GoldenItem(
                item.getString("kind"),
                item.getString("text"),
                FloatArray(vector.length()) {
                    vector.getDouble(it).toFloat()
                },
            )
        }
    }

    /** The `multi` files pushed by the device-tests workflow, copied into the app's private files. */
    private fun multiFiles(): ModelFiles {
        // The directory of the prototype test, so that the 118 MB model is copied once.
        val directory = File(context.noBackupFilesDir, "model/multi").apply { mkdirs() }
        val files = ModelFiles(File(directory, "model.onnx"), File(directory, "vocab.bin"))
        for (file in listOf(files.model, files.vocabulary)) {
            if (!file.isFile) {
                val descriptor = instrumentation.uiAutomation.executeShellCommand("cat $MULTI_PUSHED/${file.name}")
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                    file.outputStream().use { input.copyTo(it) }
                }
            }
        }
        assumeTrue(
            "multi model not pushed to $MULTI_PUSHED",
            files.model.length() > 100_000_000L && files.vocabulary.length() > 1_000_000L,
        )
        return files
    }

    private fun pssKb(): Long = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss.toLong()

    private fun measure(name: String, value: Double) {
        Log.i(TAG, "api=${Build.VERSION.SDK_INT} $name=$value")
    }

    private companion object {
        const val TAG = "SeekMindBench"
        const val MULTI_PUSHED = "/data/local/tmp/seekmind/multi"
        const val MIN_COSINE = 0.99
        const val WARMUP = 2
        const val RUNS = 7

        /** Long enough for 256 tokens after truncation, with both vocabularies. */
        val LONG_PASSAGE = List(40) {
            "La réunion du conseil municipal est reportée au jeudi $it à dix-huit heures."
        }.joinToString(" ")

        fun dot(a: FloatArray, b: FloatArray): Double = a.indices.sumOf { (a[it] * b[it]).toDouble() }

        fun norm(a: FloatArray): Double = sqrt(dot(a, a))
    }
}
