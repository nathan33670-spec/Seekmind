package fr.corsicajack.seekmind.engine.source

import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Config.TARGET_SDK])
class PackageManagerAppsTest {
    private val packageManager = RuntimeEnvironment.getApplication().packageManager
    private val apps = PackageManagerApps(packageManager)

    @Test
    fun installedApplicationsAndTheirLauncher() {
        val component = ComponentName(NOTES, "$NOTES.MainActivity")
        shadowOf(packageManager).installPackage(
            PackageInfo().apply {
                packageName = NOTES
                applicationInfo = ApplicationInfo().apply {
                    packageName = NOTES
                    nonLocalizedLabel = "Notes"
                }
            },
        )
        shadowOf(packageManager).addActivityIfNotPresent(component)
        shadowOf(packageManager).addIntentFilterForActivity(
            component,
            IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
        )

        assertThat(apps.isInstalled(NOTES)).isTrue()
        assertThat(apps.label(NOTES)).isEqualTo("Notes")
        assertThat(apps.launchIntent(NOTES)?.component).isEqualTo(component)
    }

    @Test
    fun anUninstalledApplication() {
        assertThat(apps.isInstalled("com.example.gone")).isFalse()
        assertThat(apps.label("com.example.gone")).isNull()
        assertThat(apps.launchIntent("com.example.gone")).isNull()
    }
}
