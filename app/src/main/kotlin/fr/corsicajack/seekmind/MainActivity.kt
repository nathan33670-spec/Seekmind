package fr.corsicajack.seekmind

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import fr.corsicajack.seekmind.core.database.settings.SettingsRepository
import fr.corsicajack.seekmind.core.designsystem.theme.SeekMindTheme
import fr.corsicajack.seekmind.feature.settings.SecuritySettingsRoute
import fr.corsicajack.seekmind.feature.settings.SecuritySettingsViewModel
import fr.corsicajack.seekmind.lock.AppLock
import fr.corsicajack.seekmind.lock.BiometricAuthenticator
import fr.corsicajack.seekmind.lock.LockGate
import fr.corsicajack.seekmind.lock.LockState
import fr.corsicajack.seekmind.ui.SeekMindRoot
import javax.inject.Inject

/**
 * Launcher activity, host of every screen. Its window is secure ([SecureActivity]) and its content
 * is composed only once the application lock allows it ([AppLock]).
 */
@AndroidEntryPoint
class MainActivity : SecureActivity() {
    @Inject lateinit var appLock: AppLock

    @Inject lateinit var settings: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            SeekMindTheme {
                val lockState by appLock.state.collectAsStateWithLifecycle(initialValue = LockState.PENDING)
                // Outside the gate, so that the screens come back as they were after an unlock.
                val navController = rememberNavController()
                LockGate(
                    state = lockState,
                    onUnlock = { BiometricAuthenticator.authenticate(this, appLock::onAuthenticated) },
                ) {
                    SeekMindRoot(navController = navController, settingsContent = { SettingsContent() })
                }
            }
        }
    }

    @Composable
    private fun SettingsContent() {
        val security = viewModel {
            SecuritySettingsViewModel(settings, appLock::isDeviceSecure, appLock::onAuthenticated)
        }
        SecuritySettingsRoute(viewModel = security)
    }

    override fun onStart() {
        super.onStart()
        appLock.onForeground()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) appLock.onBackground()
    }
}
