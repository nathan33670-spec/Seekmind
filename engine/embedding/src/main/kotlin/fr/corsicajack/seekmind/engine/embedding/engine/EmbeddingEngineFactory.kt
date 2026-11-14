package fr.corsicajack.seekmind.engine.embedding.engine

import android.content.Context
import fr.corsicajack.seekmind.core.model.config.EmbeddingConfig
import fr.corsicajack.seekmind.core.model.port.EmbeddingEngine
import fr.corsicajack.seekmind.engine.embedding.ModelLocation
import fr.corsicajack.seekmind.engine.embedding.OrtModelLoader
import fr.corsicajack.seekmind.engine.embedding.tokenizer.UnigramTokenizer
import fr.corsicajack.seekmind.engine.embedding.tokenizer.Vocabulary
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** Opens the engine of a model; [SharedEmbeddingEngineProvider] decides when. */
fun interface EmbeddingEngineFactory {
    suspend fun open(model: LoadableModel, intraOpThreads: Int): EmbeddingEngine
}

/**
 * Loads the vocabulary and the ONNX Runtime session of a model (docs/specs/embeddings.md §4): a bundled
 * model is copied once to `noBackupFilesDir` then opened by its path ([OrtModelLoader]); a pack is
 * opened by its file path. Runs on [io]; the engine computes on [compute].
 */
class OrtEmbeddingEngineFactory(
    private val context: Context,
    private val config: EmbeddingConfig,
    private val io: CoroutineDispatcher,
    private val compute: CoroutineDispatcher,
    private val loader: OrtModelLoader = OrtModelLoader(context),
) : EmbeddingEngineFactory {
    override suspend fun open(model: LoadableModel, intraOpThreads: Int): EmbeddingEngine = withContext(io) {
        val (location, vocabulary) = when (val source = model.source) {
            is ModelSource.Bundled -> ModelLocation.Asset("${source.directory}/$MODEL_FILE") to
                context.assets.open("${source.directory}/$VOCABULARY_FILE").use { Vocabulary.read(it) }

            is ModelSource.Pack -> ModelLocation.Path(source.files.model) to
                source.files.vocabulary.inputStream().use { Vocabulary.read(it) }
        }
        val session = loader.open(location, intraOpThreads).session
        val encoder = try {
            OrtEncoder(session)
        } catch (failure: Throwable) {
            // Whatever fails while reading the session's shape, the native session must not leak.
            session.close()
            throw failure
        }
        OrtEmbeddingEngine(model.spec, UnigramTokenizer(vocabulary), encoder, config, compute)
    }

    private companion object {
        const val MODEL_FILE = "model.onnx"
        const val VOCABULARY_FILE = "vocab.bin"
    }
}
