package fr.corsicajack.seekmind.core.model.port

import fr.corsicajack.seekmind.core.model.Capture
import fr.corsicajack.seekmind.core.model.ModelSpec
import fr.corsicajack.seekmind.core.model.PackState
import fr.corsicajack.seekmind.core.model.SearchRequest
import fr.corsicajack.seekmind.core.model.SearchResults
import fr.corsicajack.seekmind.core.model.SourceRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Sentence embeddings (docs/specs/embeddings.md §4), implemented by `engine:embedding`. The prefixes
 * ("query: ", "passage: ") are added by the engine, never by the caller.
 */
interface EmbeddingEngine : AutoCloseable {
    val modelId: Int

    /** 384. */
    val dimensions: Int

    /** Adds "passage: ". */
    suspend fun embedPassages(texts: List<String>): List<FloatArray>

    /** Adds "query: ". */
    suspend fun embedQuery(text: String): FloatArray
}

interface EmbeddingEngineProvider {
    /** Gives the engine of the active model; the model is loaded on the first call. */
    suspend fun <T> use(block: suspend (EmbeddingEngine) -> T): T
}

/** Catalogue of the embedding models, one of them active (docs/specs/embeddings.md §5.1). */
interface ModelCatalog {
    val models: List<ModelSpec>
    val active: StateFlow<ModelSpec>

    fun state(id: String): Flow<PackState>

    /** Changes the active model; triggers the recomputation of the vectors. */
    suspend fun activate(id: String)
}

/** Hybrid search (docs/specs/recherche.md §2), implemented by `engine:search`. */
interface SearchEngine {
    /** Emits the keyword results first, then the hybrid results. */
    fun search(request: SearchRequest): Flow<SearchResults>
}

/** Way back to the source of a capture (docs/specs/retour-source.md §3), implemented by `engine:source`. */
interface SourceResolver {
    fun resolve(capture: Capture): SourceRef

    /** http(s) URLs present in the text (EF-SRC-05). */
    fun linksIn(capture: Capture): List<String>
}
