package fr.corsicajack.seekmind.engine.capture.tree

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import fr.corsicajack.seekmind.core.common.IntRect
import fr.corsicajack.seekmind.core.common.Outcome
import fr.corsicajack.seekmind.core.model.DropReason

/**
 * [UiNode] over AccessibilityNodeInfo (docs/specs/capture.md §4.1). From API 33, children are fetched
 * with the hybrid prefetch strategy, which groups the inter-process calls; [release] recycles the node
 * before API 33 and does nothing afterwards.
 */
class A11yUiNode(private val info: AccessibilityNodeInfo) : UiNode {
    override val className: String? get() = info.className?.toString()
    override val viewId: String? get() = info.viewIdResourceName
    override val text: CharSequence? get() = info.text
    override val contentDescription: CharSequence? get() = info.contentDescription
    override val isVisibleToUser: Boolean get() = info.isVisibleToUser
    override val isPassword: Boolean get() = info.isPassword
    override val isEditable: Boolean get() = info.isEditable
    override val isClickable: Boolean get() = info.isClickable
    override val isFocused: Boolean get() = info.isFocused
    override val isHeading: Boolean get() = info.isHeading
    override val childCount: Int get() = info.childCount

    override val bounds: IntRect
        get() = Rect().also(info::getBoundsInScreen).let { IntRect(it.left, it.top, it.right, it.bottom) }

    override fun child(index: Int): UiNode? {
        val child = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            info.getChild(index, AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID)
        } else {
            info.getChild(index)
        }
        return child?.let(::A11yUiNode)
    }

    @Suppress("DEPRECATION")
    override fun release() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) info.recycle()
    }

    companion object {
        /**
         * The root of the active window (steps 1 and 2 of §4.2): [DropReason.NO_ROOT] without root,
         * [DropReason.NOT_APP_WINDOW] when the window is not an application's.
         */
        fun activeRoot(service: AccessibilityService): Outcome<UiNode, DropReason> {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                service.getRootInActiveWindow(AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID)
            } else {
                service.rootInActiveWindow
            } ?: return Outcome.Failure(DropReason.NO_ROOT)
            val root = A11yUiNode(info)
            val window = info.window
            val type = window?.type
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) window?.recycle()
            if (type != AccessibilityWindowInfo.TYPE_APPLICATION) {
                root.release()
                return Outcome.Failure(DropReason.NOT_APP_WINDOW)
            }
            return Outcome.Success(root)
        }
    }
}
