package fr.corsicajack.seekmind.core.common

/** A rectangle in screen pixels, independent of the Android framework. */
data class IntRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}
