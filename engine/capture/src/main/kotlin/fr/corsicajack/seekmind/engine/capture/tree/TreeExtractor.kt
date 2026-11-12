package fr.corsicajack.seekmind.engine.capture.tree

import fr.corsicajack.seekmind.core.common.Clock
import fr.corsicajack.seekmind.core.common.Outcome
import fr.corsicajack.seekmind.core.model.DropReason
import fr.corsicajack.seekmind.core.model.TextLine
import fr.corsicajack.seekmind.core.model.TreeExtraction
import fr.corsicajack.seekmind.core.model.config.CaptureConfig

/**
 * Reads the text of a window (docs/specs/capture.md §4.2): iterative pre-order depth-first walk with an
 * explicit stack, on the capture thread. Takes ownership of [extract]'s root: every node it is given or
 * obtains is released, abandon included.
 */
class TreeExtractor(private val config: CaptureConfig, private val clock: Clock) {
    fun extract(root: UiNode): Outcome<TreeExtraction, DropReason> {
        val start = clock.elapsedRealtime()
        val stack = ArrayDeque<Pair<UiNode, Int>>()
        stack.addLast(root to 0)
        val lines = mutableListOf<TextLine>()
        val seen = HashSet<String>()
        val headings = mutableListOf<String>()
        val viewIds = HashSet<String>()
        val windowBounds = root.bounds
        var nodes = 0
        var chars = 0
        var area = 0L
        var truncated = false
        var password = false
        try {
            walk@ while (stack.isNotEmpty()) {
                if (nodes > 0 && nodes % CHECK_EVERY == 0) {
                    val elapsed = clock.elapsedRealtime() - start
                    if (elapsed > config.hardBudgetMs) return Outcome.Failure(DropReason.TREE_TIMEOUT)
                    if (elapsed > config.softBudgetMs) {
                        truncated = true
                        break@walk
                    }
                }
                if (nodes >= config.maxNodes || chars >= config.maxTextChars) {
                    truncated = true
                    break@walk
                }
                val (node, depth) = stack.removeLast()
                try {
                    nodes++
                    node.viewId?.let(viewIds::add)
                    if (!node.isVisibleToUser) continue
                    if (node.isPassword) {
                        password = true
                        break@walk
                    }
                    if (node.isEditable) continue

                    val line = lineOf(node)
                    if (line != null && seen.add(line.text)) {
                        lines += line
                        chars += line.text.length
                        area +=
                            line.bounds.width.toLong().coerceAtLeast(0) * line.bounds.height.toLong().coerceAtLeast(0)
                        if (node.isHeading && !line.fromDescription) headings += line.text
                    }
                    if (depth < config.maxDepth) {
                        // Pushed in reverse, so that the first child is read first (pre-order).
                        for (i in node.childCount - 1 downTo 0) node.child(i)?.let { stack.addLast(it to depth + 1) }
                    }
                } finally {
                    node.release()
                }
            }
        } finally {
            while (stack.isNotEmpty()) stack.removeLast().first.release()
        }
        val windowArea = windowBounds.width.toLong() * windowBounds.height.toLong()
        return Outcome.Success(
            TreeExtraction(
                lines = lines,
                headings = headings,
                hasPasswordField = password,
                viewIds = viewIds,
                nodeCount = nodes,
                truncated = truncated,
                textChars = chars,
                textCoverage = if (windowArea <= 0) 0f else (area.toDouble() / windowArea).coerceAtMost(1.0).toFloat(),
                windowBounds = windowBounds,
                durationMs = clock.elapsedRealtime() - start,
            ),
        )
    }

    /** The text rule and the description rule of §4.2, then the clean-up of step 4. */
    private fun lineOf(node: UiNode): TextLine? {
        val text = node.text?.let(::clean)
        if (!text.isNullOrEmpty()) {
            return TextLine(text, node.bounds, fromDescription = false).takeIf {
                meaningful(text)
            }
        }
        val description = node.contentDescription?.let(::clean)
        if (description.isNullOrEmpty()) return null
        val keep = !node.isClickable || node.className?.contains("Image") == true
        return if (keep && meaningful(description)) TextLine(description, node.bounds, fromDescription = true) else null
    }

    private fun clean(value: CharSequence): String = WHITESPACE.replace(value, " ").trim().take(MAX_LINE_CHARS)

    private fun meaningful(text: String) = text.count(Char::isLetterOrDigit) >= MIN_ALNUM

    private companion object {
        const val CHECK_EVERY = 50
        const val MAX_LINE_CHARS = 2_000
        const val MIN_ALNUM = 2
        val WHITESPACE = Regex("\\s+")
    }
}
