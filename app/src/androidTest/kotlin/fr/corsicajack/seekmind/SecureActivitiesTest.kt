package fr.corsicajack.seekmind

import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith

/** EF-PRIV-11: every activity of the application has a secure window (ticket M6-07). */
@RunWith(AndroidJUnit4::class)
class SecureActivitiesTest {
    @Test
    fun everyDeclaredActivityExtendsSecureActivity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        @Suppress("DEPRECATION") // PackageInfoFlags needs API 33; the minimum is 30.
        val declared = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_ACTIVITIES)
            .activities
            .orEmpty()
            .map { it.name }
        val (own, others) = declared.partition { it.startsWith("fr.corsicajack.seekmind.") }

        assertThat(own).contains(MainActivity::class.java.name)
        own.forEach { name ->
            assertWithMessage("$name must extend SecureActivity")
                .that(SecureActivity::class.java.isAssignableFrom(Class.forName(name)))
                .isTrue()
        }
        // Debug builds also hold the activity of the Compose preview tooling, absent from release
        // builds (whose components :app:verify*ReleaseManifest checks). Any other one would escape
        // the rule above.
        assertThat(DEBUG_TOOLING).containsAtLeastElementsIn(others)
    }

    private companion object {
        val DEBUG_TOOLING = setOf("androidx.compose.ui.tooling.PreviewActivity")
    }
}
