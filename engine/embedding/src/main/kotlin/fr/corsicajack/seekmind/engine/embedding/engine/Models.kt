package fr.corsicajack.seekmind.engine.embedding.engine

import fr.corsicajack.seekmind.core.model.Delivery
import fr.corsicajack.seekmind.core.model.ModelSpec
import java.io.File

/** Paths of `model.onnx` and `vocab.bin` of an installed language pack (docs/specs/embeddings.md §5.1). */
data class ModelFiles(val model: File, val vocabulary: File)

/** Where the files of a model are read from. */
sealed interface ModelSource {
    /** Uncompressed assets of the APK, under [directory] (e.g. `model/fr`). */
    data class Bundled(val directory: String) : ModelSource

    data class Pack(val files: ModelFiles) : ModelSource
}

/** A model the engine can load: its catalogue entry, which gives the `modelId`, and its files. */
data class LoadableModel(val spec: ModelSpec, val source: ModelSource)

/** The model to load at the next session: the active one of the catalogue (docs/specs/embeddings.md §5.1). */
fun interface ActiveModel {
    suspend fun current(): LoadableModel
}

/** The models of docs/specs/embeddings.md §1; `modelId` values of §7. */
object BuiltInModels {
    val FR = ModelSpec(
        id = "fr",
        modelId = 1,
        languages = setOf("fr"),
        sizeBytes = 36_595_240L + 460_929L,
        delivery = Delivery.BUNDLED,
        packName = null,
    )

    val MULTI = ModelSpec(
        id = "multi",
        modelId = 2,
        languages = emptySet(),
        sizeBytes = 118_330_479L + 4_081_365L,
        delivery = Delivery.PACK,
        packName = "modelpack_multi",
    )

    val all: List<ModelSpec> = listOf(FR, MULTI)

    /** The bundled `fr` model, active until the catalogue of ticket M4-07 chooses another one. */
    val bundledFr: LoadableModel = LoadableModel(FR, ModelSource.Bundled("model/fr"))
}
