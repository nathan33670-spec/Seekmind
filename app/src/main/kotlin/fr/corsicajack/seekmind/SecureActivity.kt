package fr.corsicajack.seekmind

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity

/**
 * Base of every activity of the application (EF-PRIV-11): the window is secure, so it is kept out of
 * screenshots, screen recordings and the thumbnail of the recent apps. A test checks that every
 * activity of the manifest extends it.
 */
abstract class SecureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        super.onCreate(savedInstanceState)
    }
}
