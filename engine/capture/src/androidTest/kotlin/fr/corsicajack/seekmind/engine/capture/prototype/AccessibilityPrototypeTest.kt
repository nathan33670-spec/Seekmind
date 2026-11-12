package fr.corsicajack.seekmind.engine.capture.prototype

import android.accessibilityservice.AccessibilityService
import android.app.UiAutomation
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Prototype S3 (ticket M0-08): can an accessibility service read the fixture screens' tree, take
 * an in-memory screenshot, and recognise a FLAG_SECURE window? Measurements are logged under the
 * `SeekMindProto` tag and collected by the device-tests workflow (docs/prototypes/S3-accessibilite.md).
 */
@RunWith(AndroidJUnit4::class)
class AccessibilityPrototypeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    // Must be obtained before any other UiAutomation, otherwise accessibility services are suppressed.
    private val uiAutomation: UiAutomation =
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private val executor = Executors.newSingleThreadExecutor()

    /**
     * The service is enabled once for the whole class: disabling it between tests races with the
     * next test, which could see the previous, dying instance.
     */
    @Before
    fun enableService() {
        if (ProbeAccessibilityService.instance == null) {
            val component = "${instrumentation.context.packageName}/${ProbeAccessibilityService::class.java.name}"
            shell("settings put secure enabled_accessibility_services $component")
            shell("settings put secure accessibility_enabled 1")
        }
        waitUntil("service connected") { ProbeAccessibilityService.instance != null }
    }

    @After
    fun releaseExecutor() {
        executor.shutdown()
    }

    @Test
    fun readsTheTextOfAPlainScreen() {
        val root = open("PlainTextActivity", marker = PLAIN_MARKER)
        val start = SystemClock.elapsedRealtimeNanos()
        val tree = readTree(root)
        val durationMs = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
        measure("tree_read_ms", durationMs)
        measure("tree_nodes", tree.nodes.toDouble())
        measure("tree_text_chars", tree.text.sumOf { it.length }.toDouble())

        assertThat(tree.text.joinToString("\n")).contains("SEEKMIND_FIXTURE_PLAIN")
        assertThat(tree.text.joinToString("\n")).contains("pâtes à la truffe")
    }

    @Test
    fun takesAScreenshotInMemory() {
        val root = open("PlainTextActivity", marker = PLAIN_MARKER)
        settle()
        val start = SystemClock.elapsedRealtimeNanos()
        val shot = screenshot(root.windowId)
        measure("screenshot_ms", (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0)
        measure("screenshot_error_code", (shot.errorCode ?: 0).toDouble())

        assertThat(shot.errorCode).isNull()
        val result = shot.result!!
        try {
            val copyStart = SystemClock.elapsedRealtimeNanos()
            val software = softwareCopy(result)
            measure("screenshot_copy_ms", (SystemClock.elapsedRealtimeNanos() - copyStart) / 1_000_000.0)
            measure("screenshot_width", software.width.toDouble())
            measure("screenshot_height", software.height.toDouble())
            assertThat(software.width).isGreaterThan(0)
            software.recycle()
        } finally {
            result.hardwareBuffer.close()
        }
    }

    @Test
    fun recognisesASecureWindow() {
        val root = open("SecureActivity", marker = SECURE_MARKER)
        settle()
        // The tree of a secure window stays readable: only the screenshot reveals FLAG_SECURE (ADR-0008).
        assertThat(readTree(root).text.joinToString()).contains("SEEKMIND_FIXTURE_SECURE")

        val shot = screenshot(root.windowId)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            measure("secure_error_code", (shot.errorCode ?: -1).toDouble())
            shot.result?.hardwareBuffer?.close()
            assertThat(shot.errorCode).isEqualTo(AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW)
        } else {
            assertThat(shot.errorCode).isNull()
            val result = shot.result!!
            try {
                val bitmap = softwareCopy(result)
                val content = contentBounds(root)
                val windowRatio = darkRatio(bitmap, Rect(0, 0, bitmap.width, bitmap.height))
                val contentRatio = darkRatio(bitmap, content)
                measure("secure_dark_ratio_screen", windowRatio)
                measure("secure_dark_ratio_content", contentRatio)
                bitmap.recycle()
                assertThat(contentRatio).isAtLeast(0.98)
            } finally {
                result.hardwareBuffer.close()
            }
        }
    }

    @Test
    fun aPlainScreenIsNotDark() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val root = open("PlainTextActivity", marker = PLAIN_MARKER)
        settle()
        val result = screenshot(root.windowId).result!!
        try {
            val bitmap = softwareCopy(result)
            val ratio = darkRatio(bitmap, contentBounds(root))
            measure("plain_dark_ratio_content", ratio)
            bitmap.recycle()
            assertThat(ratio).isLessThan(0.98)
        } finally {
            result.hardwareBuffer.close()
        }
    }

    @Test
    fun imageTextIsInvisibleToTheTree() {
        val root = open("ImageTextActivity", marker = null)
        val tree = readTree(root)
        measure("image_tree_text_chars", tree.text.sumOf { it.length }.toDouble())
        assertThat(tree.text.joinToString()).doesNotContain("4821")
    }

    @Test
    fun measuresTheMinimalScreenshotInterval() {
        val root = open("PlainTextActivity", marker = PLAIN_MARKER)
        settle()
        var accepted: Long? = null
        for (delay in listOf(0L, 250L, 500L, 750L, 1_000L, 1_500L, 2_000L, 3_000L)) {
            SystemClock.sleep(RESET_MS)
            val baseline = screenshot(root.windowId)
            baseline.result?.hardwareBuffer?.close()
            SystemClock.sleep(delay)
            val shot = screenshot(root.windowId)
            shot.result?.hardwareBuffer?.close()
            measure("interval_${delay}ms_baseline_error", (baseline.errorCode ?: 0).toDouble())
            measure("interval_${delay}ms_error", (shot.errorCode ?: 0).toDouble())
            if (baseline.errorCode == null && shot.errorCode == null) {
                accepted = delay
                break
            }
        }
        measure("interval_first_accepted_ms", (accepted ?: -1L).toDouble())
        assertThat(accepted).isNotNull()
    }

    // --- helpers -------------------------------------------------------------------------------

    private class Tree(val nodes: Int, val text: List<String>)

    private class Shot(val result: AccessibilityService.ScreenshotResult?, val errorCode: Int?)

    private fun service() = checkNotNull(ProbeAccessibilityService.instance) { "service not connected" }

    /**
     * Cold-starts a fixture activity through the shell (`am start -W` returns once it is displayed),
     * then waits until the service sees that very activity and, when given, its marker text.
     */
    private fun open(activity: String, marker: String?): AccessibilityNodeInfo {
        val className = "$FIXTURE_PACKAGE.$activity"
        ProbeAccessibilityService.seenWindowClasses.clear()
        shell("am start -W -S -n $FIXTURE_PACKAGE/$className")
        var root: AccessibilityNodeInfo? = null
        waitUntil("$activity on screen") {
            root = service().rootInActiveWindow
                ?.takeIf { className in ProbeAccessibilityService.seenWindowClasses }
                ?.takeIf { it.packageName == FIXTURE_PACKAGE }
                // A screen without marker (the image screen) may expose no child node at all.
                ?.takeIf { node -> marker == null || readTree(node).text.any { it.contains(marker) } }
            root != null
        }
        return root!!
    }

    /** Lets the window finish drawing before a screenshot. */
    private fun settle() = SystemClock.sleep(SETTLE_MS)

    private fun readTree(root: AccessibilityNodeInfo): Tree {
        var nodes = 0
        val text = mutableListOf<String>()
        val stack = ArrayDeque(listOf(root))
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            nodes++
            node.text?.toString()?.takeIf { it.isNotBlank() }?.let(text::add)
            for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let(stack::addLast)
        }
        return Tree(nodes, text)
    }

    private fun screenshot(windowId: Int): Shot {
        val latch = CountDownLatch(1)
        var shot = Shot(null, -1)
        val callback = object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                shot = Shot(result, null)
                latch.countDown()
            }

            override fun onFailure(errorCode: Int) {
                shot = Shot(null, errorCode)
                latch.countDown()
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            service().takeScreenshotOfWindow(windowId, executor, callback)
        } else {
            service().takeScreenshot(Display.DEFAULT_DISPLAY, executor, callback)
        }
        check(latch.await(10, TimeUnit.SECONDS)) { "screenshot timed out" }
        return shot
    }

    private fun softwareCopy(result: AccessibilityService.ScreenshotResult): Bitmap {
        val hardware = checkNotNull(Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace))
        try {
            return hardware.copy(Bitmap.Config.ARGB_8888, false)
        } finally {
            hardware.recycle()
        }
    }

    /** Bounds of `android:id/content`, i.e. the app's own drawing area. */
    private fun contentBounds(root: AccessibilityNodeInfo): Rect {
        val content = root.findAccessibilityNodeInfosByViewId("android:id/content").firstOrNull() ?: root
        return Rect().also(content::getBoundsInScreen)
    }

    /** Share of a 24 × 24 sample grid whose luminance is below 8/255 (docs/specs/ocr.md §3.2). */
    private fun darkRatio(bitmap: Bitmap, area: Rect): Double {
        val bounds = Rect(area).apply { intersect(0, 0, bitmap.width, bitmap.height) }
        var dark = 0
        for (i in 0 until GRID) {
            for (j in 0 until GRID) {
                val x = bounds.left + (bounds.width() - 1) * i / (GRID - 1)
                val y = bounds.top + (bounds.height() - 1) * j / (GRID - 1)
                val c = bitmap.getPixel(x, y)
                val luma = 0.2126 * Color.red(c) + 0.7152 * Color.green(c) + 0.0722 * Color.blue(c)
                if (luma < 8) dark++
            }
        }
        return dark.toDouble() / (GRID * GRID)
    }

    /** Runs a shell command and waits for it to finish (its output is read to the end). */
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

    private fun measure(name: String, value: Double) {
        Log.i(TAG, "api=${Build.VERSION.SDK_INT} $name=$value")
    }

    private companion object {
        const val FIXTURE_PACKAGE = "fr.corsicajack.seekmind.fixture"
        const val PLAIN_MARKER = "SEEKMIND_FIXTURE_PLAIN"
        const val SECURE_MARKER = "SEEKMIND_FIXTURE_SECURE"
        const val TAG = "SeekMindProto"
        const val TIMEOUT_MS = 15_000L
        const val SETTLE_MS = 1_000L
        const val RESET_MS = 3_000L
        const val GRID = 24
    }
}
