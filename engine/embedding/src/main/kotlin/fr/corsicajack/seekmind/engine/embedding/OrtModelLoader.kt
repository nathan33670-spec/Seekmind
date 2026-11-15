package fr.corsicajack.seekmind.engine.embedding

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.nio.channels.FileChannel
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONException
import org.json.JSONObject

/** Where a model comes from (docs/specs/embeddings.md §4). */
sealed interface ModelLocation {
    /** Uncompressed asset of the APK, e.g. `model/fr/model.onnx`. */
    data class Asset(val path: String) : ModelLocation

    /** File of an installed language pack. */
    data class Path(val file: File) : ModelLocation
}

/** How a session was obtained; recorded by the measurements of prototype S4. */
enum class LoadPath { MEMORY_MAPPED_ASSET, COPIED_ASSET, FILE }

/** [fallbackCause]: why the asset could not be copied, when [path] is [LoadPath.MEMORY_MAPPED_ASSET]. */
class LoadedSession(val session: OrtSession, val path: LoadPath, val fallbackCause: Throwable? = null)

/**
 * Opens an ONNX Runtime session on the CPU (docs/specs/embeddings.md §4). An asset is copied once to
 * `noBackupFilesDir/model/` and opened by its path: ONNX Runtime copies the weights of a memory-mapped
 * buffer, which costs twice the model size (prototype S4). Mapping the asset is the fallback when the
 * copy fails. A pack is opened by its file path (its files are checked by its installer).
 *
 * The files of an asset are checked against its `MODEL_MANIFEST.json` whenever they are copied, that is
 * on the first load after an installation or an update (ticket M4-06), and before each mapped load. A
 * missing or altered model throws [CorruptModelException].
 */
class OrtModelLoader(
    private val context: Context,
    private val openAsset: (String) -> InputStream = { context.assets.open(it) },
) {
    private val environment: OrtEnvironment = OrtEnvironment.getEnvironment()

    // A failed check, by asset and manifest: rethrown without reading the model again, until another
    // application version brings another manifest.
    private val failures = ConcurrentHashMap<String, CorruptModelException>()

    fun open(location: ModelLocation, intraOpThreads: Int): LoadedSession {
        val options = sessionOptions(intraOpThreads)
        return when (location) {
            is ModelLocation.Path -> LoadedSession(
                environment.createSession(location.file.path, options),
                LoadPath.FILE,
            )

            is ModelLocation.Asset -> {
                val copy = try {
                    copyAsset(location.path)
                } catch (cause: IOException) {
                    verifyAsset(location.path)
                    return LoadedSession(
                        environment.createSession(mapAsset(location.path), options),
                        LoadPath.MEMORY_MAPPED_ASSET,
                        cause,
                    )
                }
                LoadedSession(environment.createSession(copy.path, options), LoadPath.COPIED_ASSET)
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
     * Every file of the manifest is checked while copying; a copy that fails the check is deleted.
     */
    fun copyAsset(path: String): File {
        val target = File(context.noBackupFilesDir, path.removePrefix("model/").let { "model/$it" })
        val directory = target.parentFile ?: throw IOException("no parent for $target")
        val (manifest, files) = manifest(path)
        val name = path.substringAfterLast('/')
        val copiedManifest = File(directory, MANIFEST)
        if (target.isFile && target.length() == files[name]?.size && copiedManifest.isFile &&
            copiedManifest.readBytes().contentEquals(manifest)
        ) {
            return target
        }
        val failureKey = path + "\n" + manifest.decodeToString()
        failures[failureKey]?.let { throw it }
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("cannot create $directory")
        copiedManifest.delete()
        val partial = File(target.path + ".part")
        try {
            files.keys.filter { it != name }.forEach { check(sibling(path, it), files[it]) }
            openChecked(path).use { input ->
                partial.outputStream().use { ModelIntegrity.copyChecked(name, input, it, files[name]) }
            }
        } catch (failure: Throwable) {
            partial.delete()
            if (failure is CorruptModelException) {
                target.delete()
                failures[failureKey] = failure
            }
            throw failure
        }
        if (!partial.renameTo(target)) throw IOException("cannot rename $partial")
        copiedManifest.writeBytes(manifest)
        return target
    }

    /** Checks every file of the manifest next to [path] without copying them. */
    fun verifyAsset(path: String) {
        val (manifest, files) = manifest(path)
        val failureKey = path + "\n" + manifest.decodeToString()
        failures[failureKey]?.let { throw it }
        try {
            if (path.substringAfterLast('/') !in files) throw CorruptModelException("$path is not in its manifest")
            files.forEach { (file, expected) -> check(sibling(path, file), expected) }
        } catch (failure: CorruptModelException) {
            failures[failureKey] = failure
            throw failure
        }
    }

    private fun check(asset: String, expected: ExpectedFile?) = openChecked(asset).use {
        ModelIntegrity.copyChecked(asset.substringAfterLast('/'), it, null, expected)
    }

    private fun sibling(path: String, name: String) = path.substringBeforeLast('/', "") + "/" + name

    /** The manifest next to [path], as bytes and as its list of files. */
    private fun manifest(path: String): Pair<ByteArray, Map<String, ExpectedFile>> {
        val bytes = openChecked(sibling(path, MANIFEST)).use { it.readBytes() }
        return try {
            val files = JSONObject(bytes.decodeToString()).getJSONObject("files")
            bytes to files.keys().asSequence().associateWith { name ->
                files.getJSONObject(name).let { ExpectedFile(it.getString("sha256"), it.getLong("size")) }
            }
        } catch (malformed: JSONException) {
            throw CorruptModelException("unreadable manifest: ${malformed.message}")
        }
    }

    private fun openChecked(asset: String): InputStream = try {
        openAsset(asset)
    } catch (missing: FileNotFoundException) {
        throw CorruptModelException("missing ${asset.substringAfterLast('/')}")
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
