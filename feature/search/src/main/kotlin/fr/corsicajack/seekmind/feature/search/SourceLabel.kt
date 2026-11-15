package fr.corsicajack.seekmind.feature.search

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import fr.corsicajack.seekmind.core.model.SourceLevel
import fr.corsicajack.seekmind.core.model.SourceRef

/**
 * The label of the return to the source: it says exactly what will happen (EF-SRC-03,
 * docs/specs/retour-source.md §1). Before the way back is resolved, the least promising one.
 */
@Composable
fun sourceLabel(source: SourceRef?, appLabel: String): String {
    val app = source?.appLabel ?: appLabel
    return when (source?.level) {
        SourceLevel.EXACT_URL -> stringResource(R.string.search_open_page)
        SourceLevel.APP_LINK -> stringResource(R.string.search_open_in, app)
        SourceLevel.APP_SEARCH -> stringResource(R.string.search_search_in, app)
        SourceLevel.APP_ONLY, null -> stringResource(R.string.search_open_app, app)
        SourceLevel.UNAVAILABLE -> stringResource(R.string.search_app_not_found)
    }
}

/** Whether [source] leads somewhere (EF-SRC-04: disabled when the application is not found). */
fun canOpen(source: SourceRef?): Boolean = source != null && source.level != SourceLevel.UNAVAILABLE
