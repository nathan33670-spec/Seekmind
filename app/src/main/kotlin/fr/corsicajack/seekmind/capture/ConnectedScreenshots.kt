package fr.corsicajack.seekmind.capture

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import fr.corsicajack.seekmind.core.common.Clock
import fr.corsicajack.seekmind.core.common.IntRect
import fr.corsicajack.seekmind.core.model.Shot
import fr.corsicajack.seekmind.core.model.config.OcrConfig
import fr.corsicajack.seekmind.engine.capture.pipeline.ConnectedService
import fr.corsicajack.seekmind.engine.ocr.probe.ProbeScreenshots
import fr.corsicajack.seekmind.engine.ocr.screenshot.A11yScreenshotSource

/**
 * The screenshots of the connected accessibility service: one [A11yScreenshotSource] per connection
 * (its rate limit follows the service), none while no service is connected. Used on the capture thread.
 */
class ConnectedScreenshots(
    private val connected: ConnectedService,
    private val config: OcrConfig,
    private val clock: Clock,
) : ProbeScreenshots {
    private var service: AccessibilityService? = null
    private var source: A11yScreenshotSource? = null

    override suspend fun <T> withScreenshot(
        windowId: Int,
        windowBounds: IntRect,
        block: suspend (Bitmap) -> T,
    ): Shot<T> = current()?.withScreenshot(windowId, windowBounds, block) ?: Shot.Failed(NOT_CONNECTED)

    override suspend fun checkWindow(windowId: Int): Shot<Unit> =
        current()?.checkWindow(windowId) ?: Shot.Failed(NOT_CONNECTED)

    private fun current(): A11yScreenshotSource? {
        val now = connected.service ?: return null.also {
            service = null
            source = null
        }
        if (now !== service) {
            service = now
            source = A11yScreenshotSource(now, config, clock)
        }
        return source
    }

    private companion object {
        /** No service: a technical failure for the probe (`Unknown`), never a "not secure". */
        const val NOT_CONNECTED = -100
    }
}
