package fr.corsicajack.seekmind.engine.search.eval

import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.corsicajack.seekmind.core.common.SystemClockJvm
import fr.corsicajack.seekmind.core.database.DatabaseHandle
import fr.corsicajack.seekmind.core.database.store.NewChunk
import fr.corsicajack.seekmind.core.database.store.NewEmbedding
import fr.corsicajack.seekmind.core.model.MatchKind
import fr.corsicajack.seekmind.core.model.SearchFilters
import fr.corsicajack.seekmind.core.model.SearchRequest
import fr.corsicajack.seekmind.core.model.config.EmbeddingConfig
import fr.corsicajack.seekmind.core.model.config.SearchConfig
import fr.corsicajack.seekmind.core.model.port.EmbeddingEngine
import fr.corsicajack.seekmind.core.model.port.EmbeddingEngineProvider
import fr.corsicajack.seekmind.core.testing.Models
import fr.corsicajack.seekmind.core.testing.TemporaryDatabase
import fr.corsicajack.seekmind.engine.embedding.codec.VectorCodec
import fr.corsicajack.seekmind.engine.embedding.engine.BuiltInModels
import fr.corsicajack.seekmind.engine.embedding.engine.LoadableModel
import fr.corsicajack.seekmind.engine.embedding.engine.ModelFiles
import fr.corsicajack.seekmind.engine.embedding.engine.ModelSource
import fr.corsicajack.seekmind.engine.embedding.engine.OrtEmbeddingEngineFactory
import fr.corsicajack.seekmind.engine.search.hybrid.HybridSearcher
import fr.corsicajack.seekmind.engine.search.hybrid.SearchCache
import fr.corsicajack.seekmind.engine.search.hybrid.VectorSemanticSource
import fr.corsicajack.seekmind.engine.search.lexical.LexicalSearcher
import fr.corsicajack.seekmind.engine.search.query.AppEntry
import fr.corsicajack.seekmind.engine.search.query.AppMatcher
import fr.corsicajack.seekmind.engine.search.query.QueryParser
import fr.corsicajack.seekmind.engine.search.query.StopWords
import fr.corsicajack.seekmind.engine.search.query.TemporalParser
import fr.corsicajack.seekmind.engine.search.vector.VectorIndex
import java.io.File
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Evaluation of the search (docs/specs/recherche.md §11, ticket M5-07), on the encrypted database with
 * the real models: testdata/eval/corpus.jsonl is stored and embedded, then every query of
 * testdata/eval/queries.jsonl runs in three configurations (keywords only, meaning only, hybrid), for
 * each model of the catalogue. Recall@1, Recall@5 and MRR, overall, by type and by language, go to
 * logcat (SeekMindBench) and to search-eval.json in the additional test output. A calibration sweep of
 * semanticFloor, binaryTopK and the weights of the natural profile is logged too.
 *
 * Fails under the thresholds of EF-SRCH-01 to 03: on the French queries for `fr`, on all of them for `multi`.
 */
@RunWith(AndroidJUnit4::class)
class SearchEvalDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val embeddingConfig = EmbeddingConfig()
    private val codec = VectorCodec(embeddingConfig.int8Scale)
    private val factory = OrtEmbeddingEngineFactory(context, embeddingConfig, Dispatchers.IO, Dispatchers.Default)

    private val corpus = readJsonl("corpus.jsonl")
    private val queryLines = readJsonl("queries.jsonl")
    private val meta = queryLines.first().getJSONObject("meta")
    private val queries = queryLines.drop(1).map { Query.from(it) }
    private val now = meta.getLong("now")
    private val zone = ZoneId.of(meta.getString("zone"))
    private val apps = meta.getJSONArray("apps").objects().map {
        AppEntry(it.getString("package"), it.getString("label"))
    }

    @Test
    fun theSearchMeetsItsThresholds() = runBlocking<Unit> {
        val report = JSONObject()
        val failures = mutableListOf<String>()
        for ((name, model) in listOf("fr" to BuiltInModels.bundledFr, "multi" to multiModel())) {
            if (model == null) {
                failures += "$name: model not available"
                continue
            }
            val modelReport = evaluate(name, model, failures)
            report.put(name, modelReport)
        }
        report.put("api", Build.VERSION.SDK_INT)
        writeReport(report)
        check(failures.isEmpty()) { "search evaluation under its thresholds:\n" + failures.joinToString("\n") }
    }

    private suspend fun evaluate(name: String, model: LoadableModel, failures: MutableList<String>): JSONObject {
        val database = TemporaryDatabase(context, "search_eval_$name")
        val handle = database.open()
        val engine = factory.open(model, embeddingConfig.threadsDefault)
        try {
            val keys = load(handle, engine)
            val provider = object : EmbeddingEngineProvider {
                override suspend fun <T> use(block: suspend (EmbeddingEngine) -> T): T = block(engine)
            }
            val result = JSONObject()
            val hybrid = run(handle, provider, keys, SearchConfig(), Mode.HYBRID)
            val keywords = run(handle, provider, keys, SearchConfig(), Mode.KEYWORDS)
            val semantic = run(handle, provider, keys, SEMANTIC_ONLY, Mode.SEMANTIC)
            for ((mode, ranks) in listOf("hybrid" to hybrid, "keywords" to keywords, "semantic" to semantic)) {
                result.put(mode, summary(ranks))
                log(name, mode, ranks)
            }
            result.put("misses_hybrid", JSONArray(queries.filter { (hybrid[it.id] ?: 0) !in 1..5 }.map { it.id }))
            result.put("sweep", sweep(handle, provider, keys, name))

            // EF-SRCH-01 to 03: the French queries for fr, every query for multi.
            val scope = if (name == "fr") queries.filter { it.lang == "fr" } else queries
            val all = Metrics.of(scope, hybrid)
            val codes = Metrics.of(scope.filter { it.type == "code" }, hybrid)
            val paraphrases = Metrics.of(scope.filter { it.type == "paraphrase" }, hybrid)
            // The values the thresholds are checked on, with the size of each group.
            for ((group, metrics, size) in listOf(
                Triple("all", all, scope.size),
                Triple("codes", codes, scope.count { it.type == "code" }),
                Triple("paraphrases", paraphrases, scope.count { it.type == "paraphrase" }),
            )) {
                Log.i(
                    TAG,
                    "api=${Build.VERSION.SDK_INT} m5_07_${name}_hybrid_scope_$group " +
                        "r1=${metrics.recall1} r5=${metrics.recall5} mrr=${metrics.mrr} n=$size",
                )
                result.put("scope_$group", metrics.json().put("n", size))
            }
            if (all.recall5 < 0.85) failures += "$name hybrid Recall@5 ${all.recall5} < 0.85"
            if (all.mrr < 0.70) failures += "$name hybrid MRR ${all.mrr} < 0.70"
            if (codes.recall1 < 0.90) failures += "$name codes Recall@1 ${codes.recall1} < 0.90"
            if (paraphrases.recall5 < 0.75) failures += "$name paraphrases Recall@5 ${paraphrases.recall5} < 0.75"
            val global = Metrics.of(queries, hybrid).recall5
            if (global < Metrics.of(queries, keywords).recall5 || global < Metrics.of(queries, semantic).recall5) {
                failures += "$name hybrid Recall@5 $global below keywords or meaning alone"
            }
            return result
        } finally {
            engine.close()
            database.delete()
        }
    }

    /** Stores and embeds the corpus; returns the key of each capture id. */
    private suspend fun load(handle: DatabaseHandle, engine: EmbeddingEngine): Map<Long, String> {
        val keys = HashMap<Long, String>()
        for (item in corpus) {
            val text = item.getString("text")
            val id = handle.captures.insert(
                Models.newCapture(
                    text = text,
                    at = item.getLong("lastSeenAt"),
                    packageName = item.getString("package"),
                    title = item.optString("title").takeIf { it.isNotEmpty() },
                    url = item.optString("url").takeIf { it.isNotEmpty() && it != "null" },
                ),
                listOf(NewChunk(0, 0, text.length)),
            )
            keys[id] = item.getString("key")
        }
        while (true) {
            val pending = handle.vectors.pendingChunks(BATCH)
            if (pending.isEmpty()) break
            val texts = pending.map { chunk -> corpusText(keys.getValue(chunk.captureId)) }
            val vectors = engine.embedPassages(texts)
            handle.vectors.saveEmbeddings(
                pending.mapIndexed { i, chunk ->
                    NewEmbedding(
                        chunk.chunkId,
                        chunk.captureId,
                        engine.modelId,
                        codec.int8(vectors[i]),
                        codec.binary(vectors[i]),
                    )
                },
            )
        }
        return keys
    }

    private val textByKey: Map<String, String> = corpus.associate { it.getString("key") to it.getString("text") }

    private fun corpusText(key: String): String = textByKey.getValue(key)

    /** The rank (1-based) of the first expected capture of each query, 0 when it is not in the results. */
    private suspend fun run(
        handle: DatabaseHandle,
        provider: EmbeddingEngineProvider,
        keys: Map<Long, String>,
        config: SearchConfig,
        mode: Mode,
    ): Map<String, Int> {
        val index = VectorIndex(handle.vectors, config, codec, Dispatchers.Default)
        val searcher = HybridSearcher(
            parser = QueryParser(StopWords.load(context.resources), TemporalParser()),
            apps = { AppMatcher(apps) },
            lexical = LexicalSearcher(handle.search),
            semantic = VectorSemanticSource(provider.takeIf { mode != Mode.KEYWORDS }, index),
            store = handle.search,
            cache = SearchCache(config, SystemClockJvm),
            generation = { handle.vectors.indexGeneration() },
            config = config,
        )
        return queries.associate { query ->
            val request = SearchRequest(query.text, SearchFilters(), limit = LIMIT, now = now, zone = zone)
            val hits = searcher.search(request).toList().last().hits
                .filter { mode != Mode.SEMANTIC || MatchKind.SEMANTIC in it.matchedBy }
            val ranked = hits.map { keys[it.captureId] }
            query.id to (ranked.indexOfFirst { it in query.expected } + 1)
        }
    }

    /** Hybrid results for nearby settings: the trials of the calibration (ticket M5-07, task 4). */
    private suspend fun sweep(
        handle: DatabaseHandle,
        provider: EmbeddingEngineProvider,
        keys: Map<Long, String>,
        model: String,
    ): JSONArray {
        val trials = buildList {
            for (floor in listOf(0.70f, 0.72f, 0.74f, 0.76f, 0.78f, 0.80f, 0.82f)) {
                add("floor=$floor" to SearchConfig(semanticFloor = floor))
            }
            add("binaryTopK=200" to SearchConfig(binaryTopK = 200))
            add("natural=1.0/1.0" to SearchConfig(lexicalWeightNatural = 1.0, semanticWeightNatural = 1.0))
            add("natural=0.6/1.4" to SearchConfig(lexicalWeightNatural = 0.6, semanticWeightNatural = 1.4))
            add("default=1.0/1.3" to SearchConfig(semanticWeightDefault = 1.3))
            add("code=2.0/1.0" to SearchConfig(semanticWeightCode = 1.0))
        }
        val scope = if (model == "fr") queries.filter { it.lang == "fr" } else queries
        return JSONArray(
            trials.map { (label, config) ->
                val ranks = run(handle, provider, keys, config, Mode.HYBRID)
                val metrics = Metrics.of(scope, ranks)
                Log.i(
                    TAG,
                    "api=${Build.VERSION.SDK_INT} m5_07_${model}_sweep $label r5=${metrics.recall5} mrr=${metrics.mrr}",
                )
                JSONObject().put("trial", label).put("recall5", metrics.recall5).put("mrr", metrics.mrr)
                    .put("recall1", metrics.recall1)
            },
        )
    }

    private fun summary(ranks: Map<String, Int>): JSONObject = JSONObject().apply {
        put("all", Metrics.of(queries, ranks).json())
        for (type in queries.map {
            it.type
        }.distinct()) {
            put("type_$type", Metrics.of(queries.filter { it.type == type }, ranks).json())
        }
        for (lang in queries.map {
            it.lang
        }.distinct()) {
            put("lang_$lang", Metrics.of(queries.filter { it.lang == lang }, ranks).json())
        }
    }

    private fun log(model: String, mode: String, ranks: Map<String, Int>) {
        val groups = listOf("all" to queries) +
            queries.map { it.type }.distinct().map { "type_$it" to queries.filter { q -> q.type == it } } +
            queries.map { it.lang }.distinct().map { "lang_$it" to queries.filter { q -> q.lang == it } }
        for ((group, scope) in groups) {
            val m = Metrics.of(scope, ranks)
            Log.i(
                TAG,
                "api=${Build.VERSION.SDK_INT} m5_07_${model}_${mode}_$group r1=${m.recall1} r5=${m.recall5} mrr=${m.mrr} n=${scope.size}",
            )
        }
    }

    private fun writeReport(report: JSONObject) {
        val directory = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?.let(::File) ?: context.getExternalFilesDir(null) ?: context.filesDir
        directory.mkdirs()
        File(directory, "search-eval.json").writeText(report.toString(2))
        Log.i(TAG, "api=${Build.VERSION.SDK_INT} m5_07_report ${File(directory, "search-eval.json")}")
    }

    private fun readJsonl(name: String): List<JSONObject> =
        instrumentation.context.assets.open(name).bufferedReader().readLines().filter {
            it.isNotBlank()
        }.map(::JSONObject)

    /** The `multi` files pushed by the device-tests workflow, copied into the app's private files. */
    private fun multiModel(): LoadableModel? {
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
        // Pushed by the device-tests workflow; without it the evaluation reports the model as missing.
        if (files.model.length() < 100_000_000L || files.vocabulary.length() < 1_000_000L) return null
        return LoadableModel(BuiltInModels.MULTI, ModelSource.Pack(files))
    }

    private enum class Mode { KEYWORDS, SEMANTIC, HYBRID }

    private data class Query(
        val id: String,
        val text: String,
        val type: String,
        val lang: String,
        val expected: Set<String>,
    ) {
        companion object {
            fun from(json: JSONObject) = Query(
                id = json.getString("id"),
                text = json.getString("text"),
                type = json.getString("type"),
                lang = json.getString("lang"),
                expected = json.getJSONArray("expected").let { array ->
                    List(array.length()) { array.getString(it) }.toSet()
                },
            )
        }
    }

    private data class Metrics(val recall1: Double, val recall5: Double, val mrr: Double) {
        fun json(): JSONObject = JSONObject().put("recall1", recall1).put("recall5", recall5).put("mrr", mrr)

        companion object {
            fun of(scope: List<Query>, ranks: Map<String, Int>): Metrics {
                if (scope.isEmpty()) return Metrics(0.0, 0.0, 0.0)
                val values = scope.map { ranks[it.id] ?: 0 }
                fun share(predicate: (Int) -> Boolean) = round(values.count(predicate).toDouble() / values.size)
                return Metrics(
                    recall1 = share { it == 1 },
                    recall5 = share { it in 1..5 },
                    mrr = round(values.sumOf { if (it > 0) 1.0 / it else 0.0 } / values.size),
                )
            }

            private fun round(value: Double) = String.format(Locale.ROOT, "%.3f", value).toDouble()
        }
    }

    private fun JSONArray.objects(): List<JSONObject> = List(length()) { getJSONObject(it) }

    private companion object {
        const val TAG = "SeekMindBench"
        const val MULTI_PUSHED = "/data/local/tmp/seekmind/multi"
        const val BATCH = 32
        const val LIMIT = 50

        /** Meaning only: the keyword list weighs nothing, and only the results found by meaning are kept. */
        val SEMANTIC_ONLY = SearchConfig(
            lexicalWeightDefault = 0.0,
            lexicalWeightCode = 0.0,
            lexicalWeightNatural = 0.0,
        )
    }
}
