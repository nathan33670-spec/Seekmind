package fr.corsicajack.seekmind.feature.search.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.corsicajack.seekmind.core.common.SmLog
import fr.corsicajack.seekmind.core.database.store.CaptureStore
import fr.corsicajack.seekmind.core.model.Capture
import fr.corsicajack.seekmind.feature.search.AppLabels
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CaptureDetailState(
    val loading: Boolean = true,
    val capture: Capture? = null,
    val appLabel: String = "",
    val highlights: List<IntRange> = emptyList(),
    val links: List<String> = emptyList(),
    /** The capture no longer exists (deleted meanwhile). */
    val missing: Boolean = false,
    /** The database cannot be opened. */
    val unavailable: Boolean = false,
    /** Deleted from this screen: the screen closes. */
    val deleted: Boolean = false,
    /** The last deletion failed: the screen stays and says so. */
    val deleteFailed: Boolean = false,
)

/**
 * Detail of a capture (docs/specs/ui-ux.md §3.3, ticket M6-04): metadata, the whole text with the
 * search [terms] highlighted, the links it contains, and its deletion. [onDeleted] tells the other
 * screens (search results, journal) that the capture is gone.
 */
class CaptureDetailViewModel(
    private val captureId: Long,
    private val terms: List<String>,
    private val store: suspend () -> CaptureStore?,
    private val labels: AppLabels,
    private val io: CoroutineDispatcher,
    private val onDeleted: suspend (Long) -> Unit,
) : ViewModel() {
    private val _state = MutableStateFlow(CaptureDetailState())
    val state: StateFlow<CaptureDetailState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val loaded = withContext(io) {
                try {
                    val captures = store() ?: return@withContext Loaded.Unavailable
                    val capture = captures.get(captureId) ?: return@withContext Loaded.Missing
                    Loaded.Found(capture, labels.label(capture.packageName))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    SmLog.w(TAG, "detail_load_failed", failure)
                    Loaded.Unavailable
                }
            }
            _state.value = when (loaded) {
                Loaded.Unavailable -> CaptureDetailState(loading = false, unavailable = true)

                Loaded.Missing -> CaptureDetailState(loading = false, missing = true)

                is Loaded.Found -> CaptureDetailState(
                    loading = false,
                    capture = loaded.capture,
                    appLabel = loaded.label,
                    highlights = TextMarks.highlights(loaded.capture.text, terms),
                    links = TextMarks.links(loaded.capture.text),
                )
            }
        }
    }

    /** After the user's confirmation: deletes the capture, then closes the screen. */
    fun delete() {
        viewModelScope.launch {
            val done = withContext(io) {
                try {
                    store()?.delete(captureId) != null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    SmLog.w(TAG, "detail_delete_failed", failure)
                    false
                }
            }
            if (done) {
                onDeleted(captureId)
                _state.update { it.copy(deleted = true, deleteFailed = false) }
            } else {
                _state.update { it.copy(deleteFailed = true) }
            }
        }
    }

    private sealed interface Loaded {
        data object Unavailable : Loaded

        data object Missing : Loaded

        data class Found(val capture: Capture, val label: String) : Loaded
    }

    private companion object {
        const val TAG = "CaptureDetail"
    }
}
