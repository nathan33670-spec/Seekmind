package fr.corsicajack.seekmind.engine.ocr.probe

import fr.corsicajack.seekmind.core.model.config.OcrConfig

/**
 * The black-image heuristic of API 30-33 (docs/specs/ocr.md §3.2): the system blacks out a secure
 * window in a screenshot. A grid of [OcrConfig.blackSampleGrid] × [OcrConfig.blackSampleGrid] pixels
 * is sampled; the image is black when at least [OcrConfig.blackRatio] of them have a luminance of at
 * most [OcrConfig.blackLumaMax] out of 255.
 */
object BlackFrame {
    /** [pixel] gives the colour at (x, y) as ARGB. */
    fun isBlack(width: Int, height: Int, config: OcrConfig, pixel: (x: Int, y: Int) -> Int): Boolean {
        if (width <= 0 || height <= 0) return false
        val grid = config.blackSampleGrid
        var dark = 0
        for (row in 0 until grid) {
            // Centre of each cell, so that the edges (status bar, rounded corners) weigh like the rest.
            val y = ((2 * row + 1) * height / (2 * grid)).coerceIn(0, height - 1)
            for (column in 0 until grid) {
                val x = ((2 * column + 1) * width / (2 * grid)).coerceIn(0, width - 1)
                if (luma(pixel(x, y)) <= config.blackLumaMax) dark++
            }
        }
        return dark >= config.blackRatio * grid * grid
    }

    /** Rec. 601 luma of an ARGB colour, 0 to 255. */
    fun luma(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (299 * r + 587 * g + 114 * b) / 1000
    }
}
