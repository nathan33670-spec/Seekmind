package fr.corsicajack.seekmind.engine.embedding.catalog

import fr.corsicajack.seekmind.core.model.Delivery
import fr.corsicajack.seekmind.core.model.ModelSpec
import fr.corsicajack.seekmind.core.model.PackState
import fr.corsicajack.seekmind.core.model.port.ModelCatalog
import fr.corsicajack.seekmind.engine.embedding.CorruptModelException
import fr.corsicajack.seekmind.engine.embedding.ExpectedFile
import fr.corsicajack.seekmind.engine.embedding.ModelIntegrity
import fr.corsicajack.seekmind.engine.embedding.engine.BuiltInModels
import fr.corsicajack.seekmind.engine.embedding.engine.LoadableModel
import fr.corsicajack.seekmind.engine.embedding.engine.ModelFiles
import fr.corsicajack.seekmind.engine.embedding.engine.ModelSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The active model setting (`activeModelId`, docs/specs/embeddings.md §5.1). */
interface ActiveModelSetting {
    suspend fun get(): String

    suspend fun set(id: String)
}

/** A pack is not installed, or its files do not match their digests: the active model does not change. */
class PackUnavailableException(message: String) : IllegalStateException(message)

/**
 * The catalogue of the embedding models (docs/specs/embeddings.md §5): `fr` bundled, active by default,
 * and the packs of [installer]. Reading the catalogue never installs anything (§5.2): only [install],
 * on an action of the user, does.
 *
 * [activate] (§5.3): checks the files of a pack against [expected], records the setting, then calls
 * [onActivated], which closes the engine session and resets the vectors (wired by `:app`). [remove] of
 * the active pack makes `fr` active again first. Both are serialised, and the digests are computed on
 * [io] (the `multi` model weighs 118 MB).
 */
class DefaultModelCatalog private constructor(
    override val models: List<ModelSpec>,
    private val installer: ModelPackInstaller,
    private val setting: ActiveModelSetting,
    private val expected: (ModelSpec) -> Map<String, ExpectedFile>?,
    private val onActivated: suspend (ModelSpec) -> Unit,
    private val io: CoroutineDispatcher,
    initial: ModelSpec,
) : ModelCatalog {
    private val changes = Mutex()
    private val activeModel = MutableStateFlow(initial)
    override val active: StateFlow<ModelSpec> = activeModel.asStateFlow()

    override fun state(id: String): Flow<PackState> {
        val spec = spec(id)
        return if (spec.delivery == Delivery.BUNDLED) flowOf(PackState.Installed) else installer.state(spec)
    }

    /** Starts the installation of a pack: on an action of the user only. */
    suspend fun install(id: String): Flow<PackState> = installer.install(spec(id))

    override suspend fun activate(id: String) = changes.withLock { activateLocked(spec(id)) }

    private suspend fun activateLocked(spec: ModelSpec) {
        if (spec == activeModel.value) return
        if (spec.delivery == Delivery.PACK) {
            val files = installer.location(spec) ?: throw PackUnavailableException("${spec.id} is not installed")
            if (!withContext(io) { matches(spec, files) }) {
                throw PackUnavailableException("${spec.id} does not match its digests")
            }
        }
        setting.set(spec.id)
        activeModel.value = spec
        onActivated(spec)
    }

    suspend fun remove(id: String) = changes.withLock {
        val spec = spec(id)
        if (spec.delivery == Delivery.BUNDLED) return@withLock
        if (activeModel.value == spec) activateLocked(BuiltInModels.FR)
        installer.remove(spec)
    }

    /** The model to load at the next session ([fr.corsicajack.seekmind.engine.embedding.engine.ActiveModel]). */
    fun loadable(): LoadableModel {
        val spec = activeModel.value
        if (spec.delivery == Delivery.BUNDLED) return BuiltInModels.bundledFr
        val files = installer.location(spec) ?: return BuiltInModels.bundledFr
        return LoadableModel(spec, ModelSource.Pack(files))
    }

    private fun spec(id: String) =
        models.firstOrNull { it.id == id } ?: throw IllegalArgumentException("unknown model $id")

    private fun matches(spec: ModelSpec, files: ModelFiles): Boolean {
        val digests = expected(spec) ?: return false
        return try {
            files.model.inputStream().use {
                ModelIntegrity.copyChecked(PackLock.MODEL_FILE, it, null, digests[PackLock.MODEL_FILE])
            }
            files.vocabulary.inputStream().use {
                ModelIntegrity.copyChecked(PackLock.VOCABULARY_FILE, it, null, digests[PackLock.VOCABULARY_FILE])
            }
            true
        } catch (corrupt: CorruptModelException) {
            false
        }
    }

    companion object {
        /**
         * The catalogue, its active model read from [setting]: `fr` when the setting names a pack that is no
         * longer installed (the setting is then set back to `fr`).
         */
        suspend fun create(
            installer: ModelPackInstaller,
            setting: ActiveModelSetting,
            io: CoroutineDispatcher,
            models: List<ModelSpec> = BuiltInModels.all,
            expected: (ModelSpec) -> Map<String, ExpectedFile>? = { PackLock.byModel[it.id] },
            onActivated: suspend (ModelSpec) -> Unit,
        ): DefaultModelCatalog {
            val stored = models.firstOrNull { it.id == setting.get() } ?: BuiltInModels.FR
            val initial = if (stored.delivery == Delivery.PACK && installer.location(stored) == null) {
                setting.set(BuiltInModels.FR.id)
                BuiltInModels.FR
            } else {
                stored
            }
            return DefaultModelCatalog(models, installer, setting, expected, onActivated, io, initial)
        }
    }
}
