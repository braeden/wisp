package com.assist.service

/**
 * In-memory [NodeView] for unit tests. Tracks [recycled] so serialization's
 * recycling contract can be asserted without the Android framework.
 */
class FakeNodeView(
    override val className: String? = "android.view.View",
    override val text: String? = null,
    override val contentDescription: String? = null,
    override val resourceId: String? = null,
    override val packageName: String? = "com.example.app",
    override val bounds: Bounds = Bounds(0, 0, 100, 50),
    override val isVisibleToUser: Boolean = true,
    override val isClickable: Boolean = false,
    override val isLongClickable: Boolean = false,
    override val isEditable: Boolean = false,
    override val isScrollable: Boolean = false,
    override val isCheckable: Boolean = false,
    override val isChecked: Boolean = false,
    override val isFocused: Boolean = false,
    override val isEnabled: Boolean = true,
    override val isPassword: Boolean = false,
    private val children: List<FakeNodeView> = emptyList(),
) : NodeView {
    var recycled: Boolean = false
        private set

    override val childCount: Int get() = children.size

    override fun child(index: Int): NodeView? = children.getOrNull(index)

    override fun performClick(): Boolean = true

    override fun performLongClick(): Boolean = true

    override fun performScrollForward(): Boolean = true

    override fun performScrollBackward(): Boolean = true

    override fun performSetText(text: String): Boolean = true

    override fun performFocus(): Boolean = true

    override fun performImeEnter(): Boolean = true

    override fun recycle() {
        recycled = true
    }

    /** All nodes in this subtree (pre-order), for recycle assertions. */
    fun subtree(): List<FakeNodeView> =
        buildList {
            add(this@FakeNodeView)
            children.forEach { addAll(it.subtree()) }
        }
}
