package fr.corsicajack.seekmind.navigation

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import fr.corsicajack.seekmind.R
import fr.corsicajack.seekmind.core.designsystem.SeekMindIcons
import kotlinx.serialization.Serializable

/** Search screen, the start destination (docs/specs/ui-ux.md §1, §2). */
@Serializable
data object SearchRoute

/** Journal of the remembered screens. */
@Serializable
data object JournalRoute

/** Settings. */
@Serializable
data object SettingsRoute

/** The three destinations of the bottom bar (docs/specs/ui-ux.md §2), in display order. */
enum class TopLevelDestination(val route: Any, @param:StringRes val label: Int, @param:DrawableRes val icon: Int) {
    SEARCH(SearchRoute, R.string.app_nav_search, SeekMindIcons.Search),
    JOURNAL(JournalRoute, R.string.app_nav_journal, SeekMindIcons.Journal),
    SETTINGS(SettingsRoute, R.string.app_nav_settings, SeekMindIcons.Settings),
}
