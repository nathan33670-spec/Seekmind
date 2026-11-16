package fr.corsicajack.seekmind.engine.ocr.probe

import fr.corsicajack.seekmind.core.model.Shot
import fr.corsicajack.seekmind.core.model.port.ScreenshotSource

/** The screenshots the probe takes: with an image, or only to learn whether the window is secure. */
interface ProbeScreenshots : ScreenshotSource {
    /**
     * Asks for a screenshot of the window and releases it at once, without copying it (API 34+,
     * docs/specs/ocr.md §3.1): [Shot.Ok] when the window is not secure.
     */
    suspend fun checkWindow(windowId: Int): Shot<Unit>
}
