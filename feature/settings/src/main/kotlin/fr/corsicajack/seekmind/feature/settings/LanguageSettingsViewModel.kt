package fr.corsicajack.seekmind.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.corsicajack.seekmind.core.model.Delivery
import fr.corsicajack.seekmind.core.model.ModelSpec
import fr.corsicajack.seekmind.core.model.PackState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch

/**
 * The model catalogue as the settings use it (docs/specs/embeddings.md §5): `:app` implements it over
 * `DefaultModelCatalog` and its installer.
 */
interface LanguagePacks {
    val models: List<ModelSpec>
    val active: StateFlow<ModelSpec>

    fun state(id: String): Flow<PackState>

    /** Starts the download: on the user's action only (§5.2). Emits until a final state. */
    suspend fun install(id: String): Flow<PackState>

    /** Whether packs come from a file (installations outside Google Play). */
    val importsFiles: Boolean

    /** Imports the `.smpack` file at [uri] for [id]: false when it is not a valid pack. */
    suspend fun import(id: String, uri: String): Boolean

    /** Checks the files, makes [id] active, empties the vectors (§5.3). */
    suspend fun activate(id: String)

    /** Deletes the pack; `fr` becomes active again if it was. */
    suspend fun remove(id: String)
}

/** A model of the catalogue, with its state. */
data class LanguageRow(val spec: ModelSpec, val state: PackState, val active: Boolean) {
    val isPack: Boolean get() = spec.delivery == Delivery.PACK
}

/** What went wrong, for a message. */
enum class LanguageError { IMPORT_INVALID, ACTIVATION_FAILED, DOWNLOAD_FAILED }

/** State of [LanguageSettingsSection]. */
data class LanguageUiState(
    val rows: List<LanguageRow>,
    val importsFiles: Boolean,
    /** Fragments still waiting for their vector: the rebuild of the index by meaning (§5.3). */
    val pendingFragments: Int,
    /** The model the user asked to use, waiting for the confirmation. */
    val confirming: ModelSpec?,
    val error: LanguageError?,
)

/**
 * « Langues de recherche » (docs/specs/ui-ux.md §3.5, EF-LANG-02 to 04): the active model, the packs and
 * their state, download, confirmation of Google Play, import of a file, use, deletion, and the progress of
 * the rebuild. Using another model asks for a confirmation first: it rebuilds the index by meaning, while
 * the search by keywords goes on. Nothing is downloaded without the user's action.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LanguageSettingsViewModel(
    private val packs: suspend () -> LanguagePacks,
    private val pendingFragments: suspend () -> Int,
    private val pollMs: Long = POLL_MS,
) : ViewModel() {
    private val catalog = MutableStateFlow<LanguagePacks?>(null)
    private val confirming = MutableStateFlow<ModelSpec?>(null)
    private val error = MutableStateFlow<LanguageError?>(null)

    private val rows: Flow<List<LanguageRow>> = catalog.flatMapLatest { current ->
        if (current == null) {
            flowOf(emptyList())
        } else {
            combine(listOf(current.active) + current.models.map { current.state(it.id) }) { values ->
                val active = values.first() as ModelSpec
                current.models.mapIndexed { i, spec -> LanguageRow(spec, values[i + 1] as PackState, spec == active) }
            }
        }
    }

    /** Polled while the screen is shown: the rebuild has no event of its own. */
    private val pending: Flow<Int> = flow {
        while (true) {
            emit(runCatching { pendingFragments() }.getOrDefault(0))
            delay(pollMs)
        }
    }

    val state: StateFlow<LanguageUiState?> = combine(catalog, rows, pending, confirming, error) {
            current,
            list,
            left,
            asked,
            failure,
        ->
        current?.let { LanguageUiState(list, it.importsFiles, left, asked, failure) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    init {
        viewModelScope.launch { catalog.value = packs() }
    }

    fun download(id: String) {
        val current = catalog.value ?: return
        viewModelScope.launch {
            try {
                var last: PackState = PackState.NotInstalled
                current.install(id).transformWhile<PackState, Unit> {
                    last = it
                    it !is PackState.Installed && it !is PackState.Failed
                }.collect()
                if (last is PackState.Failed) error.value = LanguageError.DOWNLOAD_FAILED
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error.value = LanguageError.DOWNLOAD_FAILED
            }
        }
    }

    /**
     * Google Play's confirmation of a download waiting for the user ([request] shows it, from the activity
     * of the screen: the model keeps no reference to it).
     */
    fun confirmDownload(request: suspend () -> Boolean) {
        viewModelScope.launch { request() }
    }

    fun import(id: String, uri: String) {
        val current = catalog.value ?: return
        viewModelScope.launch {
            val imported = try {
                current.import(id, uri)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            if (!imported) error.value = LanguageError.IMPORT_INVALID
        }
    }

    /** « Utiliser »: asks for the confirmation first. */
    fun askToUse(id: String) {
        val current = catalog.value ?: return
        val spec = current.models.firstOrNull { it.id == id } ?: return
        if (spec != current.active.value) confirming.value = spec
    }

    fun confirmUse() {
        val current = catalog.value ?: return
        val spec = confirming.value ?: return
        confirming.value = null
        viewModelScope.launch {
            try {
                current.activate(spec.id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error.value = LanguageError.ACTIVATION_FAILED
            }
        }
    }

    fun cancelUse() {
        confirming.value = null
    }

    fun remove(id: String) {
        val current = catalog.value ?: return
        viewModelScope.launch {
            try {
                current.remove(id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error.value = LanguageError.ACTIVATION_FAILED
            }
        }
    }

    fun errorShown() {
        error.value = null
    }

    private companion object {
        const val POLL_MS = 2_000L
    }
}
