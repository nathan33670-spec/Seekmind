package fr.corsicajack.seekmind

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import dagger.hilt.android.AndroidEntryPoint
import fr.corsicajack.seekmind.apps.PackageManagerAppLabels
import fr.corsicajack.seekmind.core.database.DatabaseState
import fr.corsicajack.seekmind.core.designsystem.theme.SeekMindTheme
import fr.corsicajack.seekmind.di.ApplicationScope
import fr.corsicajack.seekmind.feature.journal.JournalRoute
import fr.corsicajack.seekmind.feature.journal.JournalViewModel
import fr.corsicajack.seekmind.ui.SeekMindRoot
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * Launcher activity, host of every screen. Its window is secure (EF-PRIV-11): it shows remembered
 * content, so it is kept out of screenshots, screen recordings and the recent apps thumbnail.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    /** Opened on first use, off the main thread. */
    @Inject lateinit var database: Provider<DatabaseState>

    @Inject @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    override fun onCreate(savedInstanceState: Bundle?) {
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            SeekMindTheme {
                SeekMindRoot(journalContent = { JournalContent() })
            }
        }
    }

    @Composable
    private fun JournalContent() {
        val journal = viewModel {
            JournalViewModel(
                store = { (database.get() as? DatabaseState.Ready)?.database?.captures },
                labels = PackageManagerAppLabels(packageManager),
                zone = ZoneId::systemDefault,
                io = Dispatchers.IO,
                commitScope = applicationScope,
            )
        }
        JournalRoute(viewModel = journal, zone = ZoneId.systemDefault(), today = { LocalDate.now() })
    }
}
