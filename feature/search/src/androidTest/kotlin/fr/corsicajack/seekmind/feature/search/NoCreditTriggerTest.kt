package fr.corsicajack.seekmind.feature.search

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The credits sheet opens once per search refused for lack of credit (docs/specs/monetisation.md §6). */
@RunWith(AndroidJUnit4::class)
class NoCreditTriggerTest {
    @get:Rule
    val compose = createComposeRule()

    private val count = mutableIntStateOf(0)
    private var opened = 0
    private val restoration = StateRestorationTester(compose)

    private fun show() = restoration.setContent { NoCreditTrigger(count.intValue) { opened++ } }

    @Test
    fun onceForEachRefusedSearchAndNotAgainAfterARotation() {
        show()
        compose.runOnIdle { count.intValue = 1 }
        compose.runOnIdle { assertThat(opened).isEqualTo(1) }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { assertThat(opened).isEqualTo(1) }
        compose.runOnIdle { count.intValue = 2 }
        compose.runOnIdle { assertThat(opened).isEqualTo(2) }
    }

    @Test
    fun againAfterTheDeathOfTheProcess() {
        show()
        compose.runOnIdle { count.intValue = 2 }
        compose.runOnIdle { assertThat(opened).isEqualTo(1) }
        // A new process: the view model counts from 0 again, the saved state comes back.
        compose.runOnIdle { count.intValue = 0 }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { count.intValue = 1 }
        compose.runOnIdle { assertThat(opened).isEqualTo(2) }
    }
}
