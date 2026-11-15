package fr.corsicajack.seekmind.engine.ocr.probe

import android.graphics.Bitmap
import android.os.Build
import fr.corsicajack.seekmind.core.common.Clock
import fr.corsicajack.seekmind.core.common.IntRect
import fr.corsicajack.seekmind.core.database.store.SecureProbe
import fr.corsicajack.seekmind.core.database.store.SecureProbeStore
import fr.corsicajack.seekmind.core.model.OcrOutcome
import fr.corsicajack.seekmind.core.model.ProbeResult
import fr.corsicajack.seekmind.core.model.ScreenMeta
import fr.corsicajack.seekmind.core.model.Shot
import fr.corsicajack.seekmind.core.model.config.OcrConfig
import fr.corsicajack.seekmind.core.model.port.OcrEngine
import fr.corsicajack.seekmind.core.model.port.SecureWindowProbe

/**
 * [SecureWindowProbe] by screenshot (docs/specs/ocr.md §3, ADR-0008).
 *
 * - API 34+: a probe at every capture; the system refuses a secure window ([Shot.SecureWindow]).
 *   Without OCR, the frame is released at once, uncopied.
 * - API 30-33: the system blacks out a secure window, hence the [BlackFrame] heuristic. A probe at
 *   every capture for [OcrConfig.probeAlwaysPackages]; otherwise once per (package, activity) and per
 *   [OcrConfig.probeCacheTtlMs], remembered in `secure_probe`. A secure result is remembered
 *   [OcrConfig.securePositiveTtlMs].
 *
 * [store] is null while the database is not open: nothing is remembered, every capture is probed.
 */
class ScreenshotSecureWindowProbe internal constructor(
    private val shots: ProbeScreenshots,
    private val ocr: OcrEngine,
    private val store: suspend () -> SecureProbeStore?,
    private val config: OcrConfig,
    private val clock: Clock,
    private val apiLevel: Int,
    private val pixels: (Bitmap) -> PixelReader,
) : SecureWindowProbe {
    constructor(
        shots: ProbeScreenshots,
        ocr: OcrEngine,
        store: suspend () -> SecureProbeStore?,
        config: OcrConfig,
        clock: Clock,
    ) : this(shots, ocr, store, config, clock, Build.VERSION.SDK_INT, ::bitmapPixels)

    override suspend fun probe(meta: ScreenMeta, windowBounds: IntRect, needImage: Boolean): ProbeResult =
        if (apiLevel >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            probeByError(meta, windowBounds, needImage)
        } else {
            probeByImage(meta, windowBounds, needImage)
        }

    override fun rejects(meta: ScreenMeta, result: ProbeResult): Boolean = when (result) {
        ProbeResult.Secure -> true
        ProbeResult.Unknown -> meta.packageName in config.probeAlwaysPackages
        is ProbeResult.NotSecure, ProbeResult.Skipped -> false
    }

    private suspend fun probeByError(meta: ScreenMeta, bounds: IntRect, needImage: Boolean): ProbeResult {
        if (!needImage) return shots.checkWindow(meta.windowId).toResult { ProbeResult.NotSecure(null) }
        return shots.withScreenshot(meta.windowId, bounds) { ocr.recognize(it) }
            .toResult { ProbeResult.NotSecure(it) }
    }

    private suspend fun probeByImage(meta: ScreenMeta, bounds: IntRect, needImage: Boolean): ProbeResult {
        val always = meta.packageName in config.probeAlwaysPackages
        val activity = meta.activityName.orEmpty()
        val probes = store()
        val known = probes?.get(meta.packageName, activity)
        val now = clock.nowMillis()
        if (known != null) {
            val age = now - known.probedAt
            if (known.isSecure && age in 0 until config.securePositiveTtlMs) return ProbeResult.Secure
            // A screen seen as not secure is probed again only after a day, unless its image is
            // taken anyway for the OCR: the heuristic then costs nothing.
            if (!known.isSecure && !always && !needImage && age in 0 until config.probeCacheTtlMs) {
                return ProbeResult.Skipped
            }
        }
        val shot = shots.withScreenshot(meta.windowId, bounds) { bitmap ->
            val reader = pixels(bitmap)
            if (BlackFrame.isBlack(reader.width, reader.height, config, reader::pixel)) {
                Inspection(secure = true, ocr = null)
            } else {
                Inspection(secure = false, ocr = if (needImage) ocr.recognize(bitmap) else null)
            }
        }
        if (shot is Shot.Ok) probes?.put(SecureProbe(meta.packageName, activity, shot.value.secure, now))
        return shot.toResult { if (it.secure) ProbeResult.Secure else ProbeResult.NotSecure(it.ocr) }
    }

    private inline fun <T> Shot<T>.toResult(ok: (T) -> ProbeResult): ProbeResult = when (this) {
        is Shot.Ok -> ok(value)
        Shot.SecureWindow -> ProbeResult.Secure
        Shot.RateLimited, is Shot.Failed -> ProbeResult.Unknown
    }

    private class Inspection(val secure: Boolean, val ocr: OcrOutcome?)
}

/** The pixels of an image, behind an interface so that the probe is tested on the JVM. */
interface PixelReader {
    val width: Int
    val height: Int

    /** ARGB colour at (x, y). */
    fun pixel(x: Int, y: Int): Int
}

private fun bitmapPixels(bitmap: Bitmap) = object : PixelReader {
    override val width: Int = bitmap.width
    override val height: Int = bitmap.height

    override fun pixel(x: Int, y: Int): Int = bitmap.getPixel(x, y)
}
