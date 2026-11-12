package fr.corsicajack.seekmind.core.model

/** How a model reaches the device (docs/specs/embeddings.md §5.1). */
enum class Delivery { BUNDLED, PACK }

/** An entry of the model catalogue (docs/specs/embeddings.md §5.1). */
data class ModelSpec(
    /** "fr", "multi". */
    val id: String,
    /** Recorded with every vector (docs/specs/embeddings.md §7). */
    val modelId: Int,
    /** Language codes; empty means every language. */
    val languages: Set<String>,
    val sizeBytes: Long,
    val delivery: Delivery,
    /** Name of the asset pack, for [Delivery.PACK]. */
    val packName: String?,
)

sealed interface PackState {
    data object NotInstalled : PackState

    /** [progress] between 0 and 1. */
    data class Downloading(val progress: Float) : PackState

    data object Installed : PackState

    data class Failed(val code: Int) : PackState
}
