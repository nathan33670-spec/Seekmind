package fr.corsicajack.seekmind.engine.embedding.catalog

import fr.corsicajack.seekmind.core.model.ModelSpec
import fr.corsicajack.seekmind.core.model.PackState
import fr.corsicajack.seekmind.engine.embedding.CorruptModelException
import fr.corsicajack.seekmind.engine.embedding.ExpectedFile
import fr.corsicajack.seekmind.engine.embedding.ModelIntegrity
import fr.corsicajack.seekmind.engine.embedding.engine.ModelFiles
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** The end of an import of a `.smpack` archive. */
sealed interface ImportResult {
    data object Imported : ImportResult

    /** Nothing was copied. [reason] names files and sizes only, never content. */
    data class Refused(val reason: String) : ImportResult
}

/**
 * Language packs imported from a `.smpack` archive the user picked (docs/specs/embeddings.md §5.2): builds
 * for development, automated tests and distribution outside Google Play.
 *
 * The archive must hold exactly `model.onnx`, `vocab.bin` and `MODEL_MANIFEST.json`; the two model files
 * are checked against [expected] (sizes and SHA-256) while they are written to a temporary directory,
 * which becomes `root/<id>` only when every check passed: a wrong digest, a missing or unexpected entry
 * leaves nothing behind. [install] downloads nothing: the import is the action of the user.
 */
class FileModelPackInstaller(
    private val root: File,
    private val expected: (ModelSpec) -> Map<String, ExpectedFile>?,
    private val io: CoroutineDispatcher,
) : ModelPackInstaller {
    private val states = ConcurrentHashMap<String, MutableStateFlow<PackState>>()

    private fun stateOf(spec: ModelSpec) = states.getOrPut(spec.id) {
        MutableStateFlow(
            if (location(spec) !=
                null
            ) {
                PackState.Installed
            } else {
                PackState.NotInstalled
            },
        )
    }

    override fun state(spec: ModelSpec): Flow<PackState> = stateOf(spec).asStateFlow()

    override suspend fun install(spec: ModelSpec): Flow<PackState> = state(spec)

    override suspend fun remove(spec: ModelSpec) = withContext(io) {
        File(root, spec.id).deleteRecursively()
        stateOf(spec).value = PackState.NotInstalled
    }

    override fun location(spec: ModelSpec): ModelFiles? {
        val directory = File(root, spec.id)
        val model = File(directory, PackLock.MODEL_FILE)
        val vocabulary = File(directory, PackLock.VOCABULARY_FILE)
        return if (model.isFile && vocabulary.isFile) ModelFiles(model, vocabulary) else null
    }

    suspend fun import(spec: ModelSpec, archive: InputStream): ImportResult = withContext(io) {
        val files = expected(spec) ?: return@withContext ImportResult.Refused("no digests for ${spec.id}")
        val partial = File(root, "${spec.id}.part")
        partial.deleteRecursively()
        try {
            if (!partial.mkdirs()) throw IOException("cannot create the import directory")
            val seen = HashSet<String>()
            ZipInputStream(archive).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    // Only the three names of the format: no path, so nothing can be written elsewhere.
                    if (name !in ENTRIES || !seen.add(name)) throw CorruptModelException("unexpected entry")
                    File(partial, name).outputStream().use { out ->
                        if (name == PackLock.MANIFEST_FILE) {
                            ModelIntegrity.copyBounded(name, zip, out, MAX_MANIFEST_BYTES)
                        } else {
                            ModelIntegrity.copyChecked(name, zip, out, files[name])
                        }
                    }
                }
            }
            if (seen != ENTRIES) throw CorruptModelException("incomplete archive: ${ENTRIES - seen}")
            val target = File(root, spec.id)
            target.deleteRecursively()
            if (!partial.renameTo(target)) throw IOException("cannot move the pack in place")
            stateOf(spec).value = PackState.Installed
            ImportResult.Imported
        } catch (cancelled: CancellationException) {
            partial.deleteRecursively()
            throw cancelled
        } catch (failure: Exception) {
            partial.deleteRecursively()
            ImportResult.Refused(failure.message ?: failure::class.java.simpleName)
        }
    }

    private companion object {
        val ENTRIES = setOf(PackLock.MODEL_FILE, PackLock.VOCABULARY_FILE, PackLock.MANIFEST_FILE)
        const val MAX_MANIFEST_BYTES = 64 * 1024L
    }
}
