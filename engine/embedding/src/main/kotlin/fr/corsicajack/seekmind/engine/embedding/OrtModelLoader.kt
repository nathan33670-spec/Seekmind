package fr.corsicajack.seekmind.engine.embedding

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.channels.FileChannel

/** Where a model comes from (docs/specs/embeddings.md §4). */
sealed interface ModelLocation {
    /** Uncompressed asset of the APK, e.g. `model/fr/model.onnx`. */
    data class Asset(val path: String) : ModelLocation

    /** File of an installed language pack. */
    data class Path(val file: File) : ModelLocation
}

/** How a session was obtained; recorded by the measurements of prototype S4. */
enum class LoadPath { MEMORY_MAPPED_ASSET, COPIED_ASSET, FILE }

class LoadedSession(val session: OrtSession, val path: LoadPath)

/**
 * Opens an ONNX Runtime session on the CPU (docs/specs/embeddings.md §4). An asset is copied once to
 * `noBackupFilesDir/model/` and opened by its path: ONNX Runtime copies the weights of a memory-mapped
 * buffer, which costs twice the model size (prototype S4). Mapping the asset is the fallback when the
 * copy fails. A pack is opened by its file path.
 */
class OrtModelLoader(private val context: Context) {
    private val environment: OrtEnvironment = OrtEnvironment.getEnvironment()

    fun open(location: ModelLocation, intraOpThreads: Int): LoadedSession {
        val options = sessionOptions(intraOpThreads)
        return when (location) {
            is ModelLocation.Path -> LoadedSession(
                environment.createSession(location.file.path, options),
                LoadPath.FILE,
            )

            is ModelLocation.Asset -> {
                val mapped = runCatching { mapAsset(location.path) }.getOrNull()
                if (mapped != null) {
                    LoadedSession(environment.createSession(mapped, options), LoadPath.MEMORY_MAPPED_ASSET)
                } else {
                    LoadedSession(
                        environment.createSession(copyAsset(location.path).path, options),
                        LoadPath.COPIED_ASSET,
                    )
                }
            }
        }
    }

    /** Maps an uncompressed asset; throws when the entry is compressed (no file descriptor). */
    fun mapAsset(path: String): java.nio.MappedByteBuffer = context.assets.openFd(path).use { descriptor ->
        FileInputStream(descriptor.fileDescriptor).channel.use { channel ->
            channel.map(FileChannel.MapMode.READ_ONLY, descriptor.startOffset, descriptor.declaredLength)
        }
    }

    /**
     * Copies an asset to `noBackupFilesDir/model/` and reuses the copy while the `MODEL_MANIFEST.json`
     * next to the asset is unchanged; a new application version with another model triggers a new copy.
     */
    fun copyAsset(path: String): File {
        val target = File(context.noBackupFilesDir, path.removePrefix("model/").let { "model/$it" })
        val directory = target.parentFile ?: throw IOException("no parent for $target")
        val manifestAsset = path.substringBeforeLast('/', "") + "/" + MANIFEST
        val manifest = runCatching { context.assets.open(manifestAsset).use { it.readBytes() } }.getOrNull()
        val copiedManifest = File(directory, MANIFEST)
        if (target.isFile && manifest != null && copiedManifest.isFile &&
            copiedManifest.readBytes().contentEquals(manifest)
        ) {
            return target
        }
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("cannot create $directory")
        copiedManifest.delete()
        val partial = File(target.path + ".part")
        context.assets.open(path).use { input -> partial.outputStream().use { input.copyTo(it) } }
        if (!partial.renameTo(target)) throw IOException("cannot rename $partial")
        manifest?.let { copiedManifest.writeBytes(it) }
        return target
    }

    /** Session on the memory-mapped asset: the fallback path, kept public for the measurements. */
    fun openMapped(path: String, intraOpThreads: Int): LoadedSession = LoadedSession(
        environment.createSession(mapAsset(path), sessionOptions(intraOpThreads)),
        LoadPath.MEMORY_MAPPED_ASSET,
    )

    private fun sessionOptions(intraOpThreads: Int) = OrtSession.SessionOptions().apply {
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        setInterOpNumThreads(1)
        setIntraOpNumThreads(intraOpThreads)
    }

    private companion object {
        const val MANIFEST = "MODEL_MANIFEST.json"
    }
}
