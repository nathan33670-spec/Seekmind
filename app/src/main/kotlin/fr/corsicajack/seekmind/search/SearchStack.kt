package fr.corsicajack.seekmind.search

import fr.corsicajack.seekmind.core.database.DatabaseState
import fr.corsicajack.seekmind.core.model.config.SearchConfig
import fr.corsicajack.seekmind.core.model.port.SearchEngine
import fr.corsicajack.seekmind.engine.embedding.codec.VectorCodec
import fr.corsicajack.seekmind.engine.embedding.engine.SharedEmbeddingEngineProvider
import fr.corsicajack.seekmind.engine.search.catalog.AndroidAppCatalog
import fr.corsicajack.seekmind.engine.search.hybrid.HybridSearcher
import fr.corsicajack.seekmind.engine.search.hybrid.SearchCache
import fr.corsicajack.seekmind.engine.search.hybrid.VectorSemanticSource
import fr.corsicajack.seekmind.engine.search.lexical.LexicalSearcher
import fr.corsicajack.seekmind.engine.search.query.QueryParser
import fr.corsicajack.seekmind.engine.search.vector.VectorIndex
import javax.inject.Provider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The search of the process (docs/specs/recherche.md): built on the first search, off the main thread,
 * once the database is open; the providers are resolved only then (stop words and aliases are read from
 * the resources, ONNX Runtime loads its native library). While the search screen is in the foreground,
 * the embedding session is retained; 60 s after it left, the vector index and its int8 vectors are
 * released (docs/specs/recherche.md §6.1).
 */
class SearchStack(
    private val database: Provider<DatabaseState>,
    private val parser: Provider<QueryParser>,
    private val catalog: Provider<AndroidAppCatalog>,
    private val engines: Provider<SharedEmbeddingEngineProvider>,
    private val codec: VectorCodec,
    private val cache: SearchCache,
    private val config: SearchConfig,
    private val compute: CoroutineDispatcher,
    private val scope: CoroutineScope,
) {
    private class Built(val engine: SearchEngine, val index: VectorIndex, val engines: SharedEmbeddingEngineProvider)

    @Volatile private var built: Built? = null
    private var retained: AutoCloseable? = null
    private var release: Job? = null
    private var foreground = false

    /** Null when the database cannot be opened. Call off the main thread. */
    @Synchronized
    fun engine(): SearchEngine? {
        built?.let { return it.engine }
        val handle = (database.get() as? DatabaseState.Ready)?.database ?: return null
        val index = VectorIndex(handle.vectors, config, codec, compute)
        val apps = catalog.get()
        val embeddings = engines.get()
        val engine = HybridSearcher(
            parser = parser.get(),
            apps = { apps.matcher() },
            lexical = LexicalSearcher(handle.search),
            semantic = VectorSemanticSource(embeddings, index),
            store = handle.search,
            cache = cache,
            generation = { handle.vectors.indexGeneration() },
            config = config,
        )
        built = Built(engine, index, embeddings)
        if (foreground && retained == null) retained = embeddings.retain()
        return engine
    }

    /** The search screen came to the foreground. */
    @Synchronized
    fun onForeground() {
        foreground = true
        release?.cancel()
        release = null
        if (retained == null) retained = built?.engines?.retain()
    }

    /** The search screen left the foreground: the session may close when idle, the index goes in 60 s. */
    @Synchronized
    fun onBackground() {
        foreground = false
        retained?.close()
        retained = null
        release?.cancel()
        release = scope.launch {
            delay(INDEX_RELEASE_MS)
            built?.index?.release()
        }
    }

    /** After « Réinitialiser SeekMind »: the next search rebuilds everything on the new database. */
    @Synchronized
    fun reset() {
        cache.clear()
        built = null
    }

    private companion object {
        const val INDEX_RELEASE_MS = 60_000L
    }
}
