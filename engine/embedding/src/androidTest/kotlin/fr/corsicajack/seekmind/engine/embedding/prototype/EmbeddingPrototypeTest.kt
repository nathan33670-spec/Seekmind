package fr.corsicajack.seekmind.engine.embedding.prototype

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.engine.embedding.LoadPath
import fr.corsicajack.seekmind.engine.embedding.ModelLocation
import fr.corsicajack.seekmind.engine.embedding.OrtModelLoader
import java.io.File
import java.nio.LongBuffer
import kotlin.math.sqrt
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Prototype S4 (ticket M0-09): ONNX Runtime on the device, with the `fr` model memory-mapped from the
 * APK (or copied) and the `multi` model opened from a file. The models run on the token ids of
 * testdata/embedding_golden_<id>.json, without the Kotlin tokenizer. Measurements are logged under the
 * `SeekMindProto` tag (docs/prototypes/S4-embeddings.md).
 */
@RunWith(AndroidJUnit4::class)
class EmbeddingPrototypeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val loader = OrtModelLoader(context)

    @Test
    fun frMatchesTheReferenceVectors() {
        val loaded = loader.open(ModelLocation.Asset(FR_ASSET), intraOpThreads = 2)
        assertThat(loaded.path).isEqualTo(LoadPath.MEMORY_MAPPED_ASSET)
        loaded.session.use { checkAgainstGolden(it, "fr") }
    }

    @Test
    fun frCopiedFromTheAssetMatchesToo() {
        val copy = loader.copyAsset(FR_ASSET)
        try {
            OrtEnvironment.getEnvironment().createSession(copy.path, OrtSession.SessionOptions()).use {
                checkAgainstGolden(it, "fr", label = "fr_copy")
            }
        } finally {
            copy.delete()
        }
    }

    @Test
    fun multiMatchesTheReferenceVectors() {
        val file = multiModel()
        loader.open(ModelLocation.Path(file), intraOpThreads = 2).session.use { checkAgainstGolden(it, "multi") }
    }

    @Test
    fun measuresFr() {
        measureLoad("fr_mapped") { loader.open(ModelLocation.Asset(FR_ASSET), it) }
        val copyStart = SystemClock.elapsedRealtime()
        val copy = loader.copyAsset(FR_ASSET)
        measure("fr_asset_copy_ms", (SystemClock.elapsedRealtime() - copyStart).toDouble())
        try {
            measureLoad("fr_copied") { loader.open(ModelLocation.Path(copy), it) }
        } finally {
            copy.delete()
        }
        measureLatencies("fr") { loader.open(ModelLocation.Asset(FR_ASSET), it).session }
    }

    @Test
    fun measuresMulti() {
        val file = multiModel()
        measureLoad("multi_file") { loader.open(ModelLocation.Path(file), it) }
        measureLatencies("multi") { loader.open(ModelLocation.Path(file), it).session }
    }

    private fun checkAgainstGolden(session: OrtSession, model: String, label: String = model) {
        val golden =
            JSONObject(instrumentation.context.assets.open("embedding_golden_$model.json").bufferedReader().readText())
        val items = golden.getJSONArray("items")
        var lowest = 1.0
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            val ids = item.getJSONArray("inputIds").let { array -> LongArray(array.length()) { array.getLong(it) } }
            val expected = item.getJSONArray("vector").let { array ->
                FloatArray(array.length()) { array.getDouble(it).toFloat() }
            }
            val actual = embed(session, ids)
            assertThat(actual.size).isEqualTo(DIMENSIONS)
            assertThat(norm(actual)).isWithin(1e-4).of(1.0)
            lowest = minOf(lowest, dot(actual, expected) / norm(expected))
        }
        measure("${label}_golden_lowest_cosine", lowest)
        assertThat(lowest).isAtLeast(MIN_COSINE)
    }

    /** First session creation (cold), its memory, and creation times with 1, 2 and 4 threads. */
    private fun measureLoad(label: String, open: (Int) -> fr.corsicajack.seekmind.engine.embedding.LoadedSession) {
        System.gc()
        val pssBefore = pssKb()
        val start = SystemClock.elapsedRealtime()
        val first = open(2)
        measure("${label}_load_first_ms", (SystemClock.elapsedRealtime() - start).toDouble())
        embed(first.session, ids(16)) // the first run allocates the arenas
        measure("${label}_pss_delta_mb", (pssKb() - pssBefore) / 1024.0)
        measure("${label}_pss_total_mb", pssKb() / 1024.0)
        first.session.close()
        val times = DoubleArray(3) {
            val again = SystemClock.elapsedRealtime()
            open(2).session.close()
            (SystemClock.elapsedRealtime() - again).toDouble()
        }
        measure("${label}_load_warm_ms", times.sorted()[1])
    }

    private fun measureLatencies(model: String, open: (Int) -> OrtSession) {
        for (threads in THREADS) {
            open(threads).use { session ->
                for (length in LENGTHS) {
                    val input = ids(length)
                    repeat(WARMUP) { embed(session, input) }
                    val runs = DoubleArray(RUNS) {
                        val start = SystemClock.elapsedRealtimeNanos()
                        embed(session, input)
                        (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
                    }
                    runs.sort()
                    measure("${model}_t${threads}_len${length}_ms_median", runs[RUNS / 2])
                }
            }
        }
    }

    /** Masked mean of last_hidden_state, then L2 normalisation (docs/specs/embeddings.md §4). */
    private fun embed(session: OrtSession, ids: LongArray): FloatArray {
        val environment = OrtEnvironment.getEnvironment()
        val shape = longArrayOf(1, ids.size.toLong())
        val feeds = mutableMapOf<String, OnnxTensor>()
        try {
            for (name in session.inputNames) {
                val values = when (name) {
                    "input_ids" -> ids
                    "attention_mask" -> LongArray(ids.size) { 1 }
                    "token_type_ids" -> LongArray(ids.size)
                    else -> error("unexpected model input $name")
                }
                feeds[name] = OnnxTensor.createTensor(environment, LongBuffer.wrap(values), shape)
            }
            session.run(feeds).use { result ->
                @Suppress("UNCHECKED_CAST")
                val hidden = (result[0].value as Array<Array<FloatArray>>)[0]
                val pooled = FloatArray(hidden[0].size)
                for (token in hidden) for (d in pooled.indices) pooled[d] += token[d] / hidden.size
                val length = norm(pooled).toFloat()
                return FloatArray(pooled.size) { pooled[it] / length }
            }
        } finally {
            feeds.values.forEach { it.close() }
        }
    }

    /** `<s>`, then ids taken from the vocabulary range shared by both models, then `</s>`. */
    private fun ids(length: Int) = LongArray(length) { index ->
        when (index) {
            0 -> 0L
            length - 1 -> 2L
            else -> 1_000L + (index * 37L) % 30_000L
        }
    }

    /** The `multi` model pushed by the device-tests workflow, copied into the app's private files. */
    private fun multiModel(): File {
        val target = File(context.noBackupFilesDir, "model/multi/model.onnx")
        if (!target.isFile) {
            target.parentFile!!.mkdirs()
            val descriptor = instrumentation.uiAutomation.executeShellCommand("cat $MULTI_PUSHED")
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
        }
        assumeTrue("multi model not pushed to $MULTI_PUSHED", target.length() > 100_000_000L)
        return target
    }

    private fun pssKb(): Long = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss.toLong()

    private fun measure(name: String, value: Double) {
        Log.i(TAG, "api=${Build.VERSION.SDK_INT} $name=$value")
    }

    private companion object {
        const val TAG = "SeekMindProto"
        const val FR_ASSET = "model/fr/model.onnx"
        const val MULTI_PUSHED = "/data/local/tmp/seekmind/multi/model.onnx"
        const val DIMENSIONS = 384
        const val MIN_COSINE = 0.99
        val THREADS = listOf(1, 2, 4)
        val LENGTHS = listOf(16, 128, 256)
        const val WARMUP = 2
        const val RUNS = 7

        fun dot(a: FloatArray, b: FloatArray): Double = a.indices.sumOf { (a[it] * b[it]).toDouble() }

        fun norm(a: FloatArray): Double = sqrt(dot(a, a))
    }
}
