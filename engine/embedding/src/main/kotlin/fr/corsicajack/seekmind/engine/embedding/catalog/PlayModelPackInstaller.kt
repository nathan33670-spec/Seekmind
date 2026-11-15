package fr.corsicajack.seekmind.engine.embedding.catalog

import fr.corsicajack.seekmind.core.model.ModelSpec
import fr.corsicajack.seekmind.core.model.PackState
import fr.corsicajack.seekmind.engine.embedding.engine.ModelFiles
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** What Google Play reports on an asset pack (the statuses of `AssetPackStatus`). */
enum class PackStatus {
    NOT_INSTALLED,
    PENDING,
    DOWNLOADING,
    TRANSFERRING,
    WAITING_FOR_WIFI,
    REQUIRES_USER_CONFIRMATION,
    COMPLETED,
    FAILED,
    CANCELED,
}

data class PackProgress(val status: PackStatus, val bytesDownloaded: Long, val totalBytes: Long, val errorCode: Int = 0)

/**
 * The asset packs of Google Play, as [PlayModelPackInstaller] uses them: `AssetPackManager` of the
 * Play Asset Delivery library behind an interface, so that the installer is tested without Play.
 * The adapter on the library comes with the decision on its exported components (INV-12, PR of M4-07).
 */
interface AssetPackGateway {
    fun progress(pack: String): Flow<PackProgress>

    /** Asks Play to download the pack; Play shows its own confirmation for mobile data when needed. */
    suspend fun fetch(pack: String)

    suspend fun remove(pack: String)

    /** The directory of the pack's assets once installed. */
    fun assetsPath(pack: String): File?
}

/**
 * Language packs delivered by Google Play as on-demand asset packs (docs/specs/embeddings.md §5.2): Play
 * downloads, the application declares no network permission. The files of `model/<id>/` of the pack
 * are checked by the catalogue before any activation.
 */
class PlayModelPackInstaller(private val gateway: AssetPackGateway) : ModelPackInstaller {
    override fun state(spec: ModelSpec): Flow<PackState> = gateway.progress(pack(spec)).map(::toState)

    override suspend fun install(spec: ModelSpec): Flow<PackState> {
        gateway.fetch(pack(spec))
        return state(spec)
    }

    override suspend fun remove(spec: ModelSpec) = gateway.remove(pack(spec))

    override fun location(spec: ModelSpec): ModelFiles? {
        val directory = gateway.assetsPath(pack(spec))?.let { File(it, "model/${spec.id}") } ?: return null
        val model = File(directory, PackLock.MODEL_FILE)
        val vocabulary = File(directory, PackLock.VOCABULARY_FILE)
        return if (model.isFile && vocabulary.isFile) ModelFiles(model, vocabulary) else null
    }

    private fun pack(spec: ModelSpec) = checkNotNull(spec.packName) { "${spec.id} is not delivered as a pack" }

    companion object {
        fun toState(progress: PackProgress): PackState = when (progress.status) {
            PackStatus.NOT_INSTALLED -> PackState.NotInstalled

            PackStatus.COMPLETED -> PackState.Installed

            PackStatus.FAILED, PackStatus.CANCELED -> PackState.Failed(progress.errorCode)

            PackStatus.PENDING, PackStatus.DOWNLOADING, PackStatus.TRANSFERRING, PackStatus.WAITING_FOR_WIFI,
            PackStatus.REQUIRES_USER_CONFIRMATION,
            -> PackState.Downloading(
                if (progress.totalBytes >
                    0
                ) {
                    (progress.bytesDownloaded.toFloat() / progress.totalBytes).coerceIn(0f, 1f)
                } else {
                    0f
                },
            )
        }
    }
}
