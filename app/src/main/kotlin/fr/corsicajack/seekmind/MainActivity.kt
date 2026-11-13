package fr.corsicajack.seekmind

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import fr.corsicajack.seekmind.core.designsystem.theme.SeekMindTheme
import fr.corsicajack.seekmind.ui.SeekMindRoot

/**
 * Launcher activity, host of every screen. Its window is secure (EF-PRIV-11): it shows remembered
 * content, so it is kept out of screenshots, screen recordings and the recent apps thumbnail.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            SeekMindTheme {
                SeekMindRoot()
            }
        }
    }
}
