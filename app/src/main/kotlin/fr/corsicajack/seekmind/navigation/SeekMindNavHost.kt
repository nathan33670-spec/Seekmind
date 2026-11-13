package fr.corsicajack.seekmind.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import fr.corsicajack.seekmind.R
import fr.corsicajack.seekmind.core.designsystem.component.EmptyState
import fr.corsicajack.seekmind.core.designsystem.theme.Spacing

/** Test tag of the screen shown for [destination]. */
fun screenTag(destination: TopLevelDestination): String = "screen_${destination.name.lowercase()}"

/**
 * Navigation graph of [fr.corsicajack.seekmind.MainActivity]. Settings is an empty placeholder until its
 * ticket (M6-06) replaces it; [searchContent] is the search (M6-03), [journalContent] the journal (M6-05).
 */
@Composable
fun SeekMindNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    searchContent: @Composable () -> Unit = {},
    journalContent: @Composable () -> Unit = {},
) {
    NavHost(navController = navController, startDestination = SearchRoute, modifier = modifier) {
        composable<SearchRoute> {
            ContentWidth(modifier = Modifier.testTag(screenTag(TopLevelDestination.SEARCH))) { searchContent() }
        }
        composable<JournalRoute> {
            ContentWidth(modifier = Modifier.testTag(screenTag(TopLevelDestination.JOURNAL))) { journalContent() }
        }
        composable<SettingsRoute> { Placeholder(TopLevelDestination.SETTINGS) }
    }
}

/**
 * Opens a destination of the bottom bar: the back stack keeps only the start destination below it,
 * and each destination keeps its state when the user switches away and back.
 */
fun NavHostController.navigateToTopLevel(destination: TopLevelDestination) {
    navigate(destination.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** Whether [destination] is, or contains, the destination shown. */
fun NavDestination?.isOn(destination: TopLevelDestination): Boolean =
    this?.hierarchy?.any { it.hasRoute(destination.route::class) } == true

/** Centres the content and limits its width on large screens (docs/specs/ui-ux.md §5). */
@Composable
fun ContentWidth(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(modifier = Modifier.widthIn(max = Spacing.maxContentWidth).fillMaxWidth()) { content() }
    }
}

@Composable
private fun Placeholder(destination: TopLevelDestination) {
    ContentWidth(modifier = Modifier.testTag(screenTag(destination))) {
        EmptyState(
            title = stringResource(destination.label),
            message = stringResource(R.string.app_placeholder_message),
            icon = destination.icon,
        )
    }
}
