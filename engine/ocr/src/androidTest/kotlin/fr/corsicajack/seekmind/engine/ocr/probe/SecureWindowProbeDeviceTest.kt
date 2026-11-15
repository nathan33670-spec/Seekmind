package fr.corsicajack.seekmind.engine.ocr.probe

import android.app.UiAutomation
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.common.Clock
import fr.corsicajack.seekmind.core.common.IntRect
import fr.corsicajack.seekmind.core.model.OcrOutcome
import fr.corsicajack.seekmind.core.model.ProbeResult
import fr.corsicajack.seekmind.core.model.ScreenMeta
import fr.corsicajack.seekmind.core.model.config.OcrConfig
import fr.corsicajack.seekmind.core.model.port.OcrEngine
import fr.corsicajack.seekmind.engine.ocr.screenshot.A11yScreenshotSource
import fr.corsicajack.seekmind.engine.ocr.screenshot.ScreenshotProbeService
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [ScreenshotSecureWindowProbe] against the real system (ticket M3-02, docs/specs/ocr.md §3): the
 * fixture's secure screen is `Secure` and its plain screen `NotSecure`, by the system's refusal on
 * API 34+ and by the black-image heuristic on API 30-33. No cache, so that every call probes.
 */
@RunWith(AndroidJUnit4::class)
class SecureWindowProbeDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    // Must be obtained before any other UiAutomation, otherwise accessibility services are suppressed.
    private val uiAutomation: UiAutomation =
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)

    private val clock = object : Clock {
        override fun nowMillis(): Long = System.currentTimeMillis()

        override fun elapsedRealtime(): Long = SystemClock.elapsedRealtime()
    }

    private val ocr = object : OcrEngine {
        override suspend fun recognize(bitmap: Bitmap) = OcrOutcome(emptyList(), durationMs = 0)
    }

    @Before
    fun connectService() {
        if (ScreenshotProbeService.instance == null) {
            val component = "${instrumentation.context.packageName}/${ScreenshotProbeService::class.java.name}"
            shell("settings put secure enabled_accessibility_services $component")
            shell("settings put secure accessibility_enabled 1")
        }
        waitUntil("service connected") { ScreenshotProbeService.instance != null }
    }

    /** The secure screen must not stay in front of the next test class's screenshots. */
    @After
    fun closeFixture() {
        shell("am force-stop $FIXTURE_PACKAGE")
    }

    @Test
    fun theSecureScreenIsSecure() {
        assertThat(probeScreen("SecureActivity")).isEqualTo(ProbeResult.Secure)
    }

    @Test
    fun thePlainScreenIsNotSecure() {
        assertThat(probeScreen("PlainTextActivity")).isEqualTo(ProbeResult.NotSecure(null))
    }

    @Test
    fun thePlainScreenWithItsImageIsNotSecure() {
        val result = probeScreen("PlainTextActivity", needImage = true)
        assertThat(result).isInstanceOf(ProbeResult.NotSecure::class.java)
        assertThat((result as ProbeResult.NotSecure).ocr).isNotNull()
    }

    private fun probeScreen(activity: String, needImage: Boolean = false): ProbeResult {
        // -S: the fixture restarts, so that no window of the previous screen (a secure one) is left.
        shell("am start -W -S -n $FIXTURE_PACKAGE/$FIXTURE_PACKAGE.$activity")
        var meta: ScreenMeta? = null
        var bounds: IntRect? = null
        waitUntil("$activity on screen") {
            val root = service().rootInActiveWindow ?: return@waitUntil false
            if (root.packageName != FIXTURE_PACKAGE) return@waitUntil false
            meta = ScreenMeta(FIXTURE_PACKAGE, "$FIXTURE_PACKAGE.$activity", null, root.windowId, clock.nowMillis())
            bounds = Rect().also(root::getBoundsInScreen).let { IntRect(it.left, it.top, it.right, it.bottom) }
            true
        }
        // Lets the window finish drawing, and the system's own interval since any previous screenshot elapse.
        SystemClock.sleep(SETTLE_MS)
        val probe = ScreenshotSecureWindowProbe(
            shots = A11yScreenshotSource(service(), OcrConfig(), clock),
            ocr = ocr,
            store = { null },
            config = OcrConfig(),
            clock = clock,
        )
        return runBlocking { probe.probe(meta!!, bounds!!, needImage) }
    }

    private fun service() = checkNotNull(ScreenshotProbeService.instance) { "service not connected" }

    private fun shell(command: String) {
        uiAutomation.executeShellCommand(command).use { descriptor ->
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
    }

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        while (!condition()) {
            check(SystemClock.uptimeMillis() < deadline) { "timed out waiting for $what" }
            SystemClock.sleep(100)
        }
    }

    private companion object {
        const val FIXTURE_PACKAGE = "fr.corsicajack.seekmind.fixture"
        const val TIMEOUT_MS = 30_000L
        const val SETTLE_MS = 1_500L
    }
}
