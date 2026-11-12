package fr.corsicajack.seekmind.engine.capture.tree

import fr.corsicajack.seekmind.core.common.IntRect

/**
 * A node of the accessibility tree, as TreeExtractor sees it (docs/specs/capture.md §4.1).
 * `A11yUiNode` wraps AccessibilityNodeInfo; JVM tests build fake trees.
 */
interface UiNode {
    val className: String?

    /** viewIdResourceName. */
    val viewId: String?
    val text: CharSequence?
    val contentDescription: CharSequence?
    val isVisibleToUser: Boolean
    val isPassword: Boolean
    val isEditable: Boolean
    val isClickable: Boolean
    val isFocused: Boolean
    val isHeading: Boolean

    /** In screen coordinates. */
    val bounds: IntRect
    val childCount: Int

    fun child(index: Int): UiNode?

    /**
     * Nodes of this subtree whose viewIdResourceName is [viewId] (`<package>:id/<name>`), in tree
     * order. The caller releases each of them.
     */
    fun findByViewId(viewId: String): List<UiNode>

    /** recycle() before API 33, no effect afterwards. */
    fun release()
}
