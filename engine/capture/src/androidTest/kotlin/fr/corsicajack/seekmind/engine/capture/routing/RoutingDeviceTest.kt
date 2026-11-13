package fr.corsicajack.seekmind.engine.capture.routing

import android.app.UiAutomation
import android.content.Intent
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.os.StrictMode
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.common.Clock
import fr.corsicajack.seekmind.core.database.settings.Consent
import fr.corsicajack.seekmind.core.database.settings.SettingsRepository
import fr.corsicajack.seekmind.core.model.config.CaptureConfig
import fr.corsicajack.seekmind.engine.capture.meta.ActivityTracker
import fr.corsicajack.seekmind.engine.capture.meta.PackageManagerActivityLookup
import fr.corsicajack.seekmind.engine.capture.prototype.ProbeAccessibilityService
import fr.corsicajack.seekmind.engine.capture.status.ServiceConnection
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** docs/specs/capture.md §2 and §5.3 on a device (ticket M2-01). */
@RunWith(AndroidJUnit4::class)
class RoutingDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    // Must be obtained before any other UiAutomation, otherwise accessibility services are suppressed.
    private val uiAutomation: UiAutomation =
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private val context = instrumentation.targetContext
    private val clock = object : Clock {
        override fun nowMillis() = System.currentTimeMillis()

        override fun elapsedRealtime() = SystemClock.elapsedRealtime()
    }

    @Before
    fun enableService() {
        if (ProbeAccessibilityService.instance == null) {
            val component = "${context.packageName}/${ProbeAccessibilityService::class.java.name}"
            shell("settings put secure enabled_accessibility_services $component")
            shell("settings put secure accessibility_enabled 1")
        }
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (ProbeAccessibilityService.instance == null && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(100)
        }
        checkNotNull(ProbeAccessibilityService.instance) { "service not connected" }
    }

    @After
    fun deleteSettings() {
        File(context.filesDir, "datastore/$SETTINGS_FILE.preferences_pb").delete()
    }

    @Test
    fun theIgnoredSetHoldsSeekMindTheSystemUiTheLauncherAndTheKeyboard() {
        val ignored = IgnoredPackages(context).compute()
        assertThat(ignored).contains(context.packageName)
        assertThat(ignored).contains("com.android.systemui")

        val home = context.packageManager
            .queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_ALL,
            )
            .map { it.activityInfo.packageName }
        assertThat(home).isNotEmpty()
        assertThat(ignored).containsAtLeastElementsIn(home)

        Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/')
            ?.let { assertThat(ignored).contains(it) }
        Log.i(TAG, "m2_01_ignored_packages=${ignored.size}")
    }

    /** Criterion 1 of M2-01: no disk, database or tree access on the main thread. */
    @Test
    fun theMainThreadReadsNeitherDiskNorDatabase() {
        val settings = SettingsRepository.create(context, SETTINGS_FILE)
        runBlocking { settings.acceptConsent(Consent(version = 1, acceptedAt = clock.nowMillis())) }
        val thread = CaptureThread(clock)
        val connection = ServiceConnection()
        val loop = CaptureLoop(
            CaptureConfig(),
            clock,
            thread,
            ActivityTracker(PackageManagerActivityLookup(context.packageManager)),
            handler = null,
        )
        val router = EventRouter(
            thread,
            loop,
            AndroidCaptureEnvironment(thread, IgnoredPackages(context), settings, connection),
            clock,
        )
        val service = checkNotNull(ProbeAccessibilityService.instance)
        val violations = CopyOnWriteArrayList<String>()
        val durations = LongArray(EVENTS)

        instrumentation.runOnMainSync {
            val previous = StrictMode.getThreadPolicy()
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder().detectAll()
                    .penaltyListener(context.mainExecutor) { violations += it.javaClass.simpleName }
                    .build(),
            )
            try {
                router.attach(service)
            } finally {
                StrictMode.setThreadPolicy(previous)
            }
        }
        // The ignored set and the settings are read on the capture thread.
        assertThat(connection.connected.value).isTrue()
        SystemClock.sleep(SETTLE_MS)

        instrumentation.runOnMainSync {
            val previous = StrictMode.getThreadPolicy()
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder().detectAll()
                    .penaltyListener(context.mainExecutor) { violations += it.javaClass.simpleName }
                    .build(),
            )
            try {
                // The first WARMUP_EVENTS are not measured: they let the runtime compile the
                // event path, so that the percentile reflects the steady state (same protocol as
                // the M4-02 bench). StrictMode watches them too.
                for (i in 0 until WARMUP_EVENTS + EVENTS) {
                    val start = SystemClock.elapsedRealtimeNanos()
                    val packageName = if (i % 2 == 0) "com.android.systemui" else FIXTURE
                    val type = if (i % 10 == 1) {
                        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    } else {
                        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                    }
                    router.onEvent(type, packageName, "$FIXTURE.PlainTextActivity", 1)
                    if (i >= WARMUP_EVENTS) durations[i - WARMUP_EVENTS] = SystemClock.elapsedRealtimeNanos() - start
                }
                router.detach()
            } finally {
                StrictMode.setThreadPolicy(previous)
            }
        }
        // Lets the capture thread drain, then the listener report.
        val drained = CountDownLatch(1)
        thread.execute { drained.countDown() }
        assertThat(drained.await(10, TimeUnit.SECONDS)).isTrue()
        SystemClock.sleep(SETTLE_MS)

        durations.sort()
        val p99Ms = durations[(EVENTS * 99) / 100] / 1_000_000.0
        Log.i(TAG, "m2_01_router_event_p99_ms=$p99Ms")
        Log.i(TAG, "m2_01_strict_mode_violations=${violations.size}")
        assertThat(violations).isEmpty()
        assertThat(connection.connected.value).isFalse()
        // Architecture §5: at most 2 ms per event on the main thread.
        assertThat(p99Ms).isLessThan(2.0)
    }

    private fun shell(command: String) {
        uiAutomation.executeShellCommand(command).use { descriptor ->
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
    }

    private companion object {
        const val TAG = "SeekMindProto"
        const val FIXTURE = "fr.corsicajack.seekmind.fixture"
        const val SETTINGS_FILE = "routing_test_settings"
        const val WARMUP_EVENTS = 500
        const val EVENTS = 2_000
        const val SETTLE_MS = 1_000L
    }
}
