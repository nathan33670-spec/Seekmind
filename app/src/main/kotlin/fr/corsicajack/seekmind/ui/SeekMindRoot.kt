package fr.corsicajack.seekmind.ui

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import fr.corsicajack.seekmind.navigation.SeekMindNavHost
import fr.corsicajack.seekmind.navigation.TopLevelDestination
import fr.corsicajack.seekmind.navigation.isOn
import fr.corsicajack.seekmind.navigation.navigateToTopLevel

/** Test tag of the bottom bar item of [destination]. */
fun navItemTag(destination: TopLevelDestination): String = "nav_${destination.name.lowercase()}"

/**
 * Root of the user interface: the screens and the bottom bar with its three destinations
 * (docs/specs/ui-ux.md §2). Edge to edge: the scaffold applies the system bar insets.
 */
@Composable
fun SeekMindRoot(
    navController: NavHostController = rememberNavController(),
    searchContent: @Composable (openCapture: (Long) -> Unit) -> Unit = {},
    journalContent: @Composable (openCapture: (Long) -> Unit) -> Unit = {},
    captureContent: @Composable (captureId: Long, close: () -> Unit) -> Unit = { _, _ -> },
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val current = backStackEntry?.destination
    Scaffold(
        bottomBar = {
            NavigationBar {
                TopLevelDestination.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = current.isOn(destination),
                        onClick = { navController.navigateToTopLevel(destination) },
                        icon = { Icon(painter = painterResource(destination.icon), contentDescription = null) },
                        label = { Text(text = stringResource(destination.label)) },
                        modifier = Modifier.testTag(navItemTag(destination)),
                    )
                }
            }
        },
    ) { padding ->
        SeekMindNavHost(
            navController = navController,
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
            searchContent = searchContent,
            journalContent = journalContent,
            captureContent = captureContent,
        )
    }
}
