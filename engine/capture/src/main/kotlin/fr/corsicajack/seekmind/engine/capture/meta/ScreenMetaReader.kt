package fr.corsicajack.seekmind.engine.capture.meta

import fr.corsicajack.seekmind.core.common.Clock
import fr.corsicajack.seekmind.core.model.ScreenMeta
import fr.corsicajack.seekmind.core.model.TreeExtraction

/**
 * Builds the [ScreenMeta] of a capture (docs/specs/capture.md §5.1). [read] runs before the tree is read,
 * for the privacy gate's package check, with the window title only; [completed] falls back on the first
 * heading once the tree is read.
 */
class ScreenMetaReader(private val activities: ActivityTracker, private val clock: Clock) {
    fun read(packageName: String, windowId: Int, windowTitle: CharSequence?) = ScreenMeta(
        packageName = packageName,
        activityName = activities.activityOf(packageName),
        windowTitle = windowTitle?.let(::title),
        windowId = windowId,
        capturedAt = clock.nowMillis(),
    )

    fun completed(meta: ScreenMeta, extraction: TreeExtraction): ScreenMeta = if (meta.windowTitle != null) {
        meta
    } else {
        meta.copy(windowTitle = extraction.headings.firstNotNullOfOrNull(::title))
    }

    private fun title(value: CharSequence): String? =
        WHITESPACE.replace(value, " ").trim().take(MAX_TITLE_CHARS).takeIf { it.isNotEmpty() }

    private companion object {
        const val MAX_TITLE_CHARS = 200
        val WHITESPACE = Regex("\\s+")
    }
}
