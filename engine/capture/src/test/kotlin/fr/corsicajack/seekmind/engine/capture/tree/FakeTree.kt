package fr.corsicajack.seekmind.engine.capture.tree

import fr.corsicajack.seekmind.core.common.IntRect
import fr.corsicajack.seekmind.core.testing.FakeClock

/**
 * Fake accessibility trees for JVM tests (ticket M2-03): built with [tree], they record which nodes
 * were obtained and released, and can advance a clock on every [UiNode.child] call, as an
 * inter-process call would take time.
 */
class FakeTree(private val clock: FakeClock? = null, private val msPerChild: Long = 0) {
    val obtained = mutableListOf<FakeNode>()

    fun tree(build: Spec.() -> Unit): FakeNode = node(Spec().apply(build))

    internal fun node(spec: Spec): FakeNode = FakeNode(this, spec).also { obtained += it }

    internal fun onChild() {
        clock?.let { it.elapsed += msPerChild }
    }

    val unreleased: List<FakeNode> get() = obtained.filter { !it.released }

    class Spec {
        var className: String? = "android.widget.TextView"
        var viewId: String? = null
        var text: String? = null
        var description: String? = null
        var visible = true
        var password = false
        var editable = false
        var clickable = false
        var focused = false
        var heading = false
        var bounds = IntRect(0, 0, 100, 10)
        internal val children = mutableListOf<Spec>()

        fun child(build: Spec.() -> Unit) {
            children += Spec().apply(build)
        }

        /** [count] text children, "item 0", "item 1"… */
        fun items(count: Int, prefix: String = "item") = repeat(count) { i -> child { text = "$prefix $i" } }
    }
}

class FakeNode internal constructor(private val tree: FakeTree, private val spec: FakeTree.Spec) : UiNode {
    var released = false
        private set

    override val className get() = spec.className
    override val viewId get() = spec.viewId
    override val text: CharSequence? get() = spec.text
    override val contentDescription: CharSequence? get() = spec.description
    override val isVisibleToUser get() = spec.visible
    override val isPassword get() = spec.password
    override val isEditable get() = spec.editable
    override val isClickable get() = spec.clickable
    override val isFocused get() = spec.focused
    override val isHeading get() = spec.heading
    override val bounds get() = spec.bounds
    override val childCount get() = spec.children.size

    override fun child(index: Int): UiNode {
        check(!released) { "child() on a released node" }
        tree.onChild()
        return tree.node(spec.children[index])
    }

    override fun release() {
        check(!released) { "released twice" }
        released = true
    }
}
