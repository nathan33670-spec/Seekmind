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
 * Opens an ONNX Runtime session on the CPU (docs/specs/embeddings.md §4): an asset is memory-mapped
 * from the APK, or copied once to `noBackupFilesDir/model/` when it cannot be mapped (compressed entry);
 * a pack is opened by its file path.
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

    /** Copies an asset once to `noBackupFilesDir/model/`, then reuses the copy. */
    fun copyAsset(path: String): File {
        val target = File(context.noBackupFilesDir, path.removePrefix("model/").let { "model/$it" })
        if (target.isFile) return target
        target.parentFile?.mkdirs() ?: throw IOException("no parent for $target")
        val partial = File(target.path + ".part")
        context.assets.open(path).use { input -> partial.outputStream().use { input.copyTo(it) } }
        if (!partial.renameTo(target)) throw IOException("cannot rename $partial")
        return target
    }

    private fun sessionOptions(intraOpThreads: Int) = OrtSession.SessionOptions().apply {
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        setInterOpNumThreads(1)
        setIntraOpNumThreads(intraOpThreads)
    }
}
