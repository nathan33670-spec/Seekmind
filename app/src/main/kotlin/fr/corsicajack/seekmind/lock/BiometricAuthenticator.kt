package fr.corsicajack.seekmind.lock

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK
import android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import fr.corsicajack.seekmind.R

/** [DeviceSecurity] from the keyguard. */
class KeyguardDeviceSecurity(private val context: Context) : DeviceSecurity {
    override fun isSecure(): Boolean = context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true
}

/**
 * Asks for a weak biometric or the device credential (docs/specs/ui-ux.md §3.6), with the platform
 * `BiometricPrompt`: both authenticators together are supported from Android 11, the minimum SDK.
 */
object BiometricAuthenticator {
    fun authenticate(activity: Activity, onSuccess: () -> Unit) {
        val prompt = BiometricPrompt.Builder(activity)
            .setTitle(activity.getString(R.string.app_lock_prompt_title))
            .setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
            .build()
        prompt.authenticate(
            CancellationSignal(),
            activity.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
            },
        )
    }
}
