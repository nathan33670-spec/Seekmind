package fr.corsicajack.seekmind.engine.ocr.recognition

import fr.corsicajack.seekmind.core.model.OcrLine

/**
 * The lines of ML Kit worth keeping (docs/specs/ocr.md §4): at least [MIN_ALPHANUMERIC] letters or digits,
 * and a confidence of at least [minConfidence] when the line has one.
 */
class OcrLineFilter(private val minConfidence: Float) {
    fun keep(line: OcrLine): Boolean {
        val confidence = line.confidence
        if (confidence != null && confidence < minConfidence) return false
        var alphanumeric = 0
        var i = 0
        val text = line.text
        while (i < text.length) {
            val codePoint = text.codePointAt(i)
            if (Character.isLetterOrDigit(codePoint) && ++alphanumeric >= MIN_ALPHANUMERIC) return true
            i += Character.charCount(codePoint)
        }
        return false
    }

    companion object {
        const val MIN_ALPHANUMERIC = 3
    }
}
