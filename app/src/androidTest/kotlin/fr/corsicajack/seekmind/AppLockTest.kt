package fr.corsicajack.seekmind

import android.app.KeyguardManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.lock.LOCK_SCREEN_TAG
import fr.corsicajack.seekmind.navigation.TopLevelDestination
import fr.corsicajack.seekmind.navigation.screenTag
import fr.corsicajack.seekmind.ui.navItemTag
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Locked application, on a device with a real screen lock (ticket M6-07): only the neutral screen is
 * composed. The PIN is set for the test and removed afterwards.
 */
@RunWith(AndroidJUnit4::class)
class AppLockTest {
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) compose.enableAccessibilityChecks()
    }

    @After
    fun clearPin() {
        shell("locksettings clear --old $PIN")
    }

    @Test
    fun lockedNoContentIsComposed() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithTag(LOCK_SCREEN_TAG).assertIsDisplayed()

            TopLevelDestination.entries.forEach { destination ->
                compose.onNodeWithTag(screenTag(destination), useUnmergedTree = true).assertDoesNotExist()
                compose.onNodeWithTag(navItemTag(destination), useUnmergedTree = true).assertDoesNotExist()
            }
            // Every text of the tree belongs to the neutral screen.
            val allowed = setOf(
                context.getString(R.string.app_name),
                context.getString(R.string.app_lock_message),
                context.getString(R.string.app_lock_unlock),
            )
            val texts = compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes()
                .flatMap { root -> root.descendants() }
                .flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
            assertThat(texts).isNotEmpty()
            assertThat(allowed).containsAtLeastElementsIn(texts)
        }
    }

    private fun SemanticsNode.descendants(): List<SemanticsNode> = listOf(this) + children.flatMap { it.descendants() }

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
    }

    private companion object {
        const val PIN = "1357"
    }
}
