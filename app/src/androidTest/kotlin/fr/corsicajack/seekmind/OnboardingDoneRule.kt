package fr.corsicajack.seekmind

import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import fr.corsicajack.seekmind.di.CaptureEntryPoint
import kotlinx.coroutines.runBlocking
import org.junit.rules.ExternalResource

/**
 * Marks the welcome flow as gone through before the activity starts, for the tests of the screens that
 * follow it. Ordered before the compose rule, which launches the activity.
 */
class OnboardingDoneRule : ExternalResource() {
    override fun before() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking {
            EntryPointAccessors.fromApplication(context, CaptureEntryPoint::class.java).settings().setOnboardingDone()
        }
    }
}
