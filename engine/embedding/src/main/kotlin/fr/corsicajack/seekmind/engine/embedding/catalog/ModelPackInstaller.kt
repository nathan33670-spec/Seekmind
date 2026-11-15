package fr.corsicajack.seekmind.engine.embedding.catalog

import fr.corsicajack.seekmind.core.model.ModelSpec
import fr.corsicajack.seekmind.core.model.PackState
import fr.corsicajack.seekmind.engine.embedding.engine.ModelFiles
import kotlinx.coroutines.flow.Flow

/** Installation of a language pack (docs/specs/embeddings.md §5.1, §5.2). */
interface ModelPackInstaller {
    /** The state of the pack of [spec], kept up to date. Reading it never starts a download. */
    fun state(spec: ModelSpec): Flow<PackState>

    /** Starts the installation: only ever on an action of the user. */
    suspend fun install(spec: ModelSpec): Flow<PackState>

    suspend fun remove(spec: ModelSpec)

    /** Paths of `model.onnx` and `vocab.bin` once installed. */
    fun location(spec: ModelSpec): ModelFiles?
}
