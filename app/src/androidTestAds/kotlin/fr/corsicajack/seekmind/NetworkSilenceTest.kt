package fr.corsicajack.seekmind

import android.app.Activity
import android.app.UiAutomation
import android.net.TrafficStats
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dagger.hilt.android.EntryPointAccessors
import fr.corsicajack.seekmind.core.database.settings.Consent
import fr.corsicajack.seekmind.core.database.settings.DISCLOSURE_VERSION
import fr.corsicajack.seekmind.core.model.AppFamily
import fr.corsicajack.seekmind.core.model.PipelineEvent
import fr.corsicajack.seekmind.core.model.SearchRequest
import fr.corsicajack.seekmind.di.AdsEntryPoint
import fr.corsicajack.seekmind.di.CaptureEntryPoint
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The network silence of the `ads` variant (docs/specs/monetisation.md §3.4, INV-01): the application
 * starts, the service captures a screen and a search runs without a single byte sent by the
 * application's uid. Only [RewardProvider.prepare][fr.corsicajack.seekmind.core.model.port.RewardProvider.prepare]
 * moves the counter.
 */
@RunWith(AndroidJUnit4::class)
class NetworkSilenceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    // Must be obtained before any other UiAutomation, otherwise accessibility services are suppressed.
    private val uiAutomation: UiAutomation =
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private val context = instrumentation.targetContext
    private val capture = EntryPointAccessors.fromApplication(context, CaptureEntryPoint::class.java)
    private val ads = EntryPointAccessors.fromApplication(context, AdsEntryPoint::class.java)
    private val service = "${context.packageName}/fr.corsicajack.seekmind.engine.capture.SeekMindAccessibilityService"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val events = Channel<PipelineEvent>(Channel.UNLIMITED)
    private val uid = Process.myUid()

    private var services: String? = null
    private var accessibility: String? = null

    @get:Rule
    val onboardingDone = OnboardingDoneRule()

    @Before
    fun setUp() = runBlocking<Unit> {
        services = setting("enabled_accessibility_services")
        accessibility = setting("accessibility_enabled")
        scope.launch { capture.pipeline().events.collect { events.send(it) } }
        capture.settings().acceptConsent(
            Consent(DISCLOSURE_VERSION, System.currentTimeMillis()),
            AppFamily.entries.toSet(),
        )
    }

    @After
    fun tearDown() = runBlocking<Unit> {
        shell("am force-stop $FIXTURE")
        restore("enabled_accessibility_services", services)
        restore("accessibility_enabled", accessibility)
        capture.settings().reset()
        scope.cancel()
    }

    @Test
    fun nothingIsSentBeforeAnAdIsAskedFor() = runBlocking<Unit> {
        val atStart = TrafficStats.getUidTxBytes(uid)
        assertWithMessage("per-uid traffic statistics are unavailable on this device")
            .that(atStart).isNotEqualTo(TrafficStats.UNSUPPORTED.toLong())
        // Since the start of the process: the SDK's start-up component would have run before this test.
        assertWithMessage("bytes sent by the application before the test").that(atStart).isEqualTo(0L)

        val scenario = ActivityScenario.launch(MainActivity::class.java)
        shell("settings put secure enabled_accessibility_services $service")
        shell("settings put secure accessibility_enabled 1")
        shell("am start -W -S -n $FIXTURE/$FIXTURE.PlainTextActivity")
        withTimeout(TIMEOUT_MS) {
            while (true) {
                val event = events.receive()
                if (event is PipelineEvent.Stored || event is PipelineEvent.Deduplicated) break
            }
        }
        val engine = checkNotNull(ads.searchStack().engine()) { "database unavailable" }
        engine.search(
            SearchRequest("zanzibar", now = System.currentTimeMillis(), zone = ZoneId.systemDefault()),
        ).first()
        // A window for anything started in the background, an SDK included, to reach the network.
        SystemClock.sleep(OBSERVATION_MS)
        assertWithMessage("bytes sent without an ad asked for")
            .that(TrafficStats.getUidTxBytes(uid) - atStart).isEqualTo(0L)

        // Asking for an ad is the one thing that reaches the network.
        var activity: Activity? = null
        scenario.onActivity { activity = it }
        ads.rewardProvider().prepare(checkNotNull(activity))
        SystemClock.sleep(COUNTER_DELAY_MS)
        assertThat(TrafficStats.getUidTxBytes(uid)).isGreaterThan(atStart)
        scenario.close()
    }

    private fun setting(name: String): String? = shell("settings get secure $name").trim().takeIf {
        it.isNotEmpty() &&
            it != "null"
    }

    private fun restore(name: String, value: String?) {
        shell(if (value == null) "settings delete secure $name" else "settings put secure $name $value")
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(command)).use {
            it.readBytes().decodeToString()
        }

    private companion object {
        const val FIXTURE = "fr.corsicajack.seekmind.fixture"
        const val TIMEOUT_MS = 60_000L
        const val OBSERVATION_MS = 10_000L
        const val COUNTER_DELAY_MS = 2_000L
    }
}
