package fr.corsicajack.seekmind.engine.search.catalog

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.engine.search.query.AppAliases
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/** The catalogue on a device (ticket M5-03): launchable applications, and their refresh. */
@RunWith(AndroidJUnit4::class)
class AndroidAppCatalogTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val catalog = AndroidAppCatalog(context, AppAliases.load(context.resources), Dispatchers.IO)

    @Test
    fun listsTheLaunchableApplications() = runBlocking {
        val apps = catalog.apps()
        // The system settings are launchable on every emulator image.
        val settings = apps.single { it.packageName == SETTINGS }
        assertThat(settings.label).isNotEmpty()
        assertThat(apps.map { it.packageName }).containsNoDuplicates()

        val match = catalog.matcher().extract("wifi dans ${settings.label}")
        assertThat(match?.packages).containsExactly(SETTINGS)
    }

    @Test
    fun anInstallationOrARemovalReadsTheApplicationsAgain() = runBlocking {
        catalog.register()
        try {
            val before = catalog.matcher()
            assertThat(catalog.matcher()).isSameInstanceAs(before)
            catalog.receiver.onReceive(context, Intent(Intent.ACTION_PACKAGE_ADDED))
            assertThat(catalog.matcher()).isNotSameInstanceAs(before)
        } finally {
            catalog.close()
        }
    }

    private companion object {
        const val SETTINGS = "com.android.settings"
    }
}
