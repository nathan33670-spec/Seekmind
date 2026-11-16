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
        // Any other activity would escape the rule above.
        assertThat(DEBUG_TOOLING + PLAY_DIALOG).containsAtLeastElementsIn(others)
    }

    private companion object {
        /** The Compose preview tooling, in debug builds only. */
        val DEBUG_TOOLING = setOf("androidx.compose.ui.tooling.PreviewActivity")

        /**
         * Play Asset Delivery (M4-07, ADR-0012): a transparent, non-exported activity that only starts
         * the Play Store's own confirmation dialog for a language pack. It shows no content of
         * SeekMind; removing it would break the pack's confirmation on mobile data.
         */
        val PLAY_DIALOG = setOf("com.google.android.play.core.common.PlayCoreDialogWrapperActivity")
    }
}
