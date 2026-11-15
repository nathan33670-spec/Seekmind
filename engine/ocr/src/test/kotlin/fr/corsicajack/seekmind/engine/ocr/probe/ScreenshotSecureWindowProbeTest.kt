package fr.corsicajack.seekmind.engine.ocr.probe

import android.graphics.Bitmap
import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.common.IntRect
import fr.corsicajack.seekmind.core.database.store.SecureProbe
import fr.corsicajack.seekmind.core.database.store.SecureProbeStore
import fr.corsicajack.seekmind.core.model.OcrOutcome
import fr.corsicajack.seekmind.core.model.ProbeResult
import fr.corsicajack.seekmind.core.model.ScreenMeta
import fr.corsicajack.seekmind.core.model.Shot
import fr.corsicajack.seekmind.core.model.config.OcrConfig
import fr.corsicajack.seekmind.core.model.port.OcrEngine
import fr.corsicajack.seekmind.core.testing.FakeClock
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** The probe's policy by API level, its cache and the handling of `Unknown` (docs/specs/ocr.md §3). */
class ScreenshotSecureWindowProbeTest {
    private val browser = "org.example.browser"
    private val app = "org.example.notes"
    private val config = OcrConfig(probeAlwaysPackages = setOf(browser))
    private val clock = FakeClock()
    private val bounds = IntRect(0, 0, 1_080, 2_400)
    private val outcome = OcrOutcome(emptyList(), durationMs = 12)

    /** What the system answers; counts the screenshots with and without an image. */
    private class Shots : ProbeScreenshots {
        var answer: Shot<Unit> = Shot.Ok(Unit)
        var images = 0
        var checks = 0

        @Suppress("UNCHECKED_CAST")
        override suspend fun <T> withScreenshot(
            windowId: Int,
            windowBounds: IntRect,
            block: suspend (Bitmap) -> T,
        ): Shot<T> {
            images++
            val result = answer
            return if (result is Shot.Ok) Shot.Ok(block(BITMAP)) else result as Shot<T>
        }

        override suspend fun checkWindow(windowId: Int): Shot<Unit> {
            checks++
            return answer
        }
    }

    private class Store : SecureProbeStore {
        val rows = mutableMapOf<Pair<String, String>, SecureProbe>()

        override suspend fun get(packageName: String, activityName: String) = rows[packageName to activityName]

        override suspend fun put(probe: SecureProbe) {
            rows[probe.packageName to probe.activityName] = probe
        }

        override suspend fun deleteOlderThan(before: Long): Int = 0
    }

    private companion object {
        /**
         * A placeholder: on the JVM, Bitmap is a stub that cannot be constructed. The fakes of the pixels
         * and of the OCR never read it.
         */
        val BITMAP: Bitmap = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").let { field ->
            field.isAccessible = true
            val unsafe = field.get(null)
            unsafe.javaClass.getMethod(
                "allocateInstance",
                Class::class.java,
            ).invoke(unsafe, Bitmap::class.java) as Bitmap
        }
    }

    private val shots = Shots()
    private val store = Store()
    private var black = false
    private var recognitions = 0
    private val ocr = object : OcrEngine {
        override suspend fun recognize(bitmap: Bitmap): OcrOutcome {
            recognitions++
            return outcome
        }
    }
    private val pixels = object : PixelReader {
        override val width = 100
        override val height = 100

        override fun pixel(x: Int, y: Int) = if (black) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    }

    private fun probe(api: Int, withStore: Boolean = true) =
        ScreenshotSecureWindowProbe(shots, ocr, { store.takeIf { withStore } }, config, clock, api) { pixels }

    private fun meta(pkg: String = app, activity: String? = "org.example.Main") =
        ScreenMeta(pkg, activity, windowTitle = null, windowId = 3, capturedAt = clock.now)

    private fun run(api: Int, meta: ScreenMeta = meta(), needImage: Boolean = false) =
        runBlocking { probe(api).probe(meta, bounds, needImage) }

    // API 34+

    @Test
    fun `api 34 - a refused screenshot is a secure window`() {
        shots.answer = Shot.SecureWindow
        assertThat(run(34)).isEqualTo(ProbeResult.Secure)
        assertThat(run(34, needImage = true)).isEqualTo(ProbeResult.Secure)
        assertThat(recognitions).isEqualTo(0)
    }

    @Test
    fun `api 34 - without OCR the frame is not copied, with OCR the same screenshot serves`() {
        assertThat(run(34)).isEqualTo(ProbeResult.NotSecure(null))
        assertThat(shots.checks).isEqualTo(1)
        assertThat(shots.images).isEqualTo(0)
        assertThat(run(34, needImage = true)).isEqualTo(ProbeResult.NotSecure(outcome))
        assertThat(shots.images).isEqualTo(1)
    }

    @Test
    fun `api 34 - every capture is probed, even a cached screen`() {
        repeat(3) { run(34) }
        assertThat(shots.checks).isEqualTo(3)
        assertThat(store.rows).isEmpty()
    }

    @Test
    fun `api 34 - a failed screenshot is unknown`() {
        shots.answer = Shot.RateLimited
        assertThat(run(34)).isEqualTo(ProbeResult.Unknown)
        shots.answer = Shot.Failed(1)
        assertThat(run(34, needImage = true)).isEqualTo(ProbeResult.Unknown)
    }

    // API 30-33

    @Test
    fun `api 30 - a black image is a secure window, remembered ten minutes`() {
        black = true
        assertThat(run(30)).isEqualTo(ProbeResult.Secure)
        assertThat(store.rows.values.single().isSecure).isTrue()
        clock.advance(config.securePositiveTtlMs - 1)
        assertThat(run(30)).isEqualTo(ProbeResult.Secure)
        assertThat(shots.images).isEqualTo(1)
        black = false
        clock.advance(1)
        assertThat(run(30)).isEqualTo(ProbeResult.NotSecure(null))
        assertThat(shots.images).isEqualTo(2)
    }

    @Test
    fun `api 30 - a light screen is not secure, and its OCR uses the same image`() {
        assertThat(run(30, needImage = true)).isEqualTo(ProbeResult.NotSecure(outcome))
        assertThat(shots.images).isEqualTo(1)
        assertThat(recognitions).isEqualTo(1)
    }

    @Test
    fun `api 30 - a black image is never sent to the OCR`() {
        black = true
        assertThat(run(30, needImage = true)).isEqualTo(ProbeResult.Secure)
        assertThat(recognitions).isEqualTo(0)
    }

    @Test
    fun `api 30 - an app off the list is probed once per screen and per day`() {
        assertThat(run(30)).isEqualTo(ProbeResult.NotSecure(null))
        clock.advance(config.probeCacheTtlMs - 1)
        assertThat(run(30)).isEqualTo(ProbeResult.Skipped)
        assertThat(shots.images).isEqualTo(1)
        // Another activity of the same app is another screen.
        assertThat(run(30, meta(activity = "org.example.Other"))).isEqualTo(ProbeResult.NotSecure(null))
        clock.advance(1)
        assertThat(run(30)).isEqualTo(ProbeResult.NotSecure(null))
        assertThat(shots.images).isEqualTo(3)
    }

    @Test
    fun `api 30 - a browser is probed at every capture`() {
        repeat(3) { assertThat(run(30, meta(browser))).isEqualTo(ProbeResult.NotSecure(null)) }
        assertThat(shots.images).isEqualTo(3)
    }

    @Test
    fun `api 30 - a cached screen whose image is taken for the OCR is checked again`() {
        run(30)
        black = true
        assertThat(run(30, needImage = true)).isEqualTo(ProbeResult.Secure)
        assertThat(recognitions).isEqualTo(0)
    }

    @Test
    fun `api 30 - without the database every capture is probed`() {
        repeat(2) { runBlocking { probe(30, withStore = false).probe(meta(), bounds, needImage = false) } }
        assertThat(shots.images).isEqualTo(2)
    }

    @Test
    fun `api 30 - a failed screenshot is unknown and remembers nothing`() {
        shots.answer = Shot.Failed(1)
        assertThat(run(30)).isEqualTo(ProbeResult.Unknown)
        assertThat(store.rows).isEmpty()
    }

    // Unknown (§3.3)

    @Test
    fun `unknown rejects the capture for a listed app only`() {
        val probe = probe(34)
        assertThat(probe.rejects(meta(browser), ProbeResult.Unknown)).isTrue()
        assertThat(probe.rejects(meta(app), ProbeResult.Unknown)).isFalse()
        assertThat(probe.rejects(meta(app), ProbeResult.Secure)).isTrue()
        assertThat(probe.rejects(meta(browser), ProbeResult.NotSecure(null))).isFalse()
        assertThat(probe.rejects(meta(browser), ProbeResult.Skipped)).isFalse()
    }

    @Test
    fun `the listed apps are the browsers and the ephemeral messaging apps`() {
        val always = EphemeralMessaging.probeAlways(setOf(browser))
        assertThat(always).contains(browser)
        assertThat(always).containsAtLeast("org.thoughtcrime.securesms", "com.whatsapp", "org.telegram.messenger")
    }
}
