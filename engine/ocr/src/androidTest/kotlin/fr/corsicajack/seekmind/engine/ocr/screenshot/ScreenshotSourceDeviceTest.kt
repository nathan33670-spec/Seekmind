package fr.corsicajack.seekmind.engine.ocr.screenshot

import android.app.UiAutomation
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Debug
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.common.Clock
import fr.corsicajack.seekmind.core.common.IntRect
import fr.corsicajack.seekmind.core.model.Shot
import fr.corsicajack.seekmind.core.model.config.OcrConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [A11yScreenshotSource] against the real system (ticket M3-01, docs/specs/ocr.md §2, INV-03): the
 * bitmap is usable only during the block, it is recycled even when the block fails, close requests are
 * refused, and 200 screenshots in a row leave no native memory behind.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotSourceDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    // Must be obtained before any other UiAutomation, otherwise accessibility services are suppressed.
    private val uiAutomation: UiAutomation =
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)

    private val clock = object : Clock {
        override fun nowMillis(): Long = System.currentTimeMillis()

        override fun elapsedRealtime(): Long = SystemClock.elapsedRealtime()
    }

    private lateinit var windowBounds: IntRect
    private var windowId = -1

    @Before
    fun openPlainScreen() {
        if (ScreenshotProbeService.instance == null) {
            val component = "${instrumentation.context.packageName}/${ScreenshotProbeService::class.java.name}"
            shell("settings put secure enabled_accessibility_services $component")
            shell("settings put secure accessibility_enabled 1")
        }
        waitUntil("service connected") { ScreenshotProbeService.instance != null }
        shell("am start -W -n $FIXTURE_PACKAGE/$FIXTURE_PACKAGE.PlainTextActivity")
        waitUntil("fixture on screen") {
            val root = service().rootInActiveWindow ?: return@waitUntil false
            if (root.packageName != FIXTURE_PACKAGE) return@waitUntil false
            windowId = root.windowId
            windowBounds = Rect().also(root::getBoundsInScreen).let { IntRect(it.left, it.top, it.right, it.bottom) }
            true
        }
        // Lets the window finish drawing, and the system's own interval since any previous screenshot elapse.
        SystemClock.sleep(SETTLE_MS)
    }

    @Test
    fun theBitmapIsAliveOnlyDuringTheBlock() = runBlocking<Unit> {
        var seen: Bitmap? = null
        val shot = source().withScreenshot(windowId, windowBounds) { bitmap ->
            assertThat(bitmap.isRecycled).isFalse()
            assertThat(bitmap.config).isEqualTo(Bitmap.Config.ARGB_8888)
            assertThat(maxOf(bitmap.width, bitmap.height)).isAtMost(OcrConfig().maxImageSidePx)
            assertThat(bitmap.width).isGreaterThan(0)
            bitmap.getPixel(bitmap.width / 2, bitmap.height / 2) // a software bitmap: pixels are readable
            seen = bitmap
            bitmap.width
        }
        assertThat(shot).isInstanceOf(Shot.Ok::class.java)
        assertThat(seen!!.isRecycled).isTrue()
    }

    @Test
    fun theBitmapIsRecycledWhenTheBlockFails() = runBlocking<Unit> {
        var seen: Bitmap? = null
        val source = source()
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                source.withScreenshot(windowId, windowBounds) {
                    seen = it
                    error("block failed")
                }
            }
        }
        assertThat(seen!!.isRecycled).isTrue()
    }

    @Test
    fun aCloseSecondRequestIsRateLimited() = runBlocking<Unit> {
        val source = source()
        assertThat(source.withScreenshot(windowId, windowBounds) { 1 }).isEqualTo(Shot.Ok(1))
        var called = false
        assertThat(source.withScreenshot(windowId, windowBounds) { called = true }).isEqualTo(Shot.RateLimited)
        assertThat(called).isFalse()
    }

    @Test
    fun twoHundredScreenshotsLeaveNoNativeMemoryBehind() = runBlocking<Unit> {
        val source = source()
        var ok = 0
        var attempts = 0
        suspend fun once() {
            attempts++
            if (source.withScreenshot(windowId, windowBounds) { it.width } is Shot.Ok) ok++
            SystemClock.sleep(INTERVAL_MS)
        }
        repeat(WARM_UP) { once() }
        val before = nativeHeap()
        ok = 0
        while (ok < SHOTS && attempts < MAX_ATTEMPTS) once()
        val growth = nativeHeap() - before
        Log.i(TAG, "api=${Build.VERSION.SDK_INT} screenshot_native_growth_bytes=$growth shots=$ok attempts=$attempts")
        assertThat(ok).isEqualTo(SHOTS)
        assertThat(growth).isLessThan(MAX_GROWTH_BYTES)
    }

    private fun source() = A11yScreenshotSource(service(), OcrConfig(), clock)

    private fun service() = checkNotNull(ScreenshotProbeService.instance) { "service not connected" }

    private fun nativeHeap(): Long {
        repeat(3) {
            Runtime.getRuntime().gc()
            System.runFinalization()
        }
        return Debug.getNativeHeapAllocatedSize()
    }

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
        const val TAG = "SeekMindProto"
        const val TIMEOUT_MS = 30_000L
        const val SETTLE_MS = 1_500L
        const val INTERVAL_MS = 1_100L
        const val WARM_UP = 5
        const val SHOTS = 200
        const val MAX_ATTEMPTS = 260
        const val MAX_GROWTH_BYTES = 10L * 1024 * 1024
    }
}
