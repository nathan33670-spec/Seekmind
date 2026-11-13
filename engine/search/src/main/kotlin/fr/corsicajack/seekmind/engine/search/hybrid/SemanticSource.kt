package fr.corsicajack.seekmind.engine.search.hybrid

import fr.corsicajack.seekmind.core.model.SearchFilters
import fr.corsicajack.seekmind.core.model.port.EmbeddingEngineProvider
import fr.corsicajack.seekmind.engine.search.vector.SemanticHit
import fr.corsicajack.seekmind.engine.search.vector.VectorIndex

/** The semantic branch of the hybrid search (docs/specs/recherche.md §6, §9). */
interface SemanticSource {
    /** False without an embedding engine: the search is keyword only, in a single emission. Cheap. */
    fun isConfigured(): Boolean

    /** False before the first vector. May load the index: called in the semantic branch, never before the keyword results. */
    suspend fun hasVectors(): Boolean

    /** The vector of the query (`query: ` prefix added by the engine). */
    suspend fun queryVector(text: String): FloatArray

    suspend fun search(query: FloatArray, filters: SearchFilters): List<SemanticHit>
}

/** [SemanticSource] on the active embedding engine and the in-memory vector index. */
class VectorSemanticSource(private val engines: EmbeddingEngineProvider?, private val index: VectorIndex) :
    SemanticSource {
    override fun isConfigured(): Boolean = engines != null

    override suspend fun hasVectors(): Boolean = !index.isEmpty()

    override suspend fun queryVector(text: String): FloatArray =
        checkNotNull(engines) { "no embedding engine" }.use { it.embedQuery(text) }

    override suspend fun search(query: FloatArray, filters: SearchFilters): List<SemanticHit> =
        index.search(query, filters)
}
