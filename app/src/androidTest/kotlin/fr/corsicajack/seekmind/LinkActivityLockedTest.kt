package fr.corsicajack.seekmind

import android.app.KeyguardManager
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.link.LINK_CONFIRM_TAG
import fr.corsicajack.seekmind.link.LINK_MESSAGE_TAG
import fr.corsicajack.seekmind.link.LinkActivity
import fr.corsicajack.seekmind.lock.LOCK_SCREEN_TAG
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A link received while the application is locked (docs/specs/ui-ux.md §3.7, ticket M8-05): only the
 * neutral lock screen is composed, no confirmation and no message, until the unlock. The PIN is set for
 * the test and removed afterwards, as in [AppLockTest].
 */
@RunWith(AndroidJUnit4::class)
class LinkActivityLockedTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Before
    fun setPin() {
        shell("locksettings set-pin $PIN")
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!keyguard.isDeviceSecure && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        assertThat(keyguard.isDeviceSecure).isTrue()
    }

    @After
    fun clearPin() {
        shell("locksettings clear --old $PIN")
    }

    @Test
    fun aLinkReceivedWhileLockedShowsOnlyTheLockScreen() {
        val link = Intent(Intent.ACTION_VIEW, Uri.parse("seekmind://activate?t=a.b.c"))
            .setClass(context, LinkActivity::class.java)
        ActivityScenario.launch<LinkActivity>(link).use {
            compose.onNodeWithTag(LOCK_SCREEN_TAG).assertIsDisplayed()
            compose.onNodeWithTag(LINK_CONFIRM_TAG, useUnmergedTree = true).assertDoesNotExist()
            compose.onNodeWithTag(LINK_MESSAGE_TAG, useUnmergedTree = true).assertDoesNotExist()
        }
    }

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
    }

    private companion object {
        const val PIN = "1357"
    }
}
