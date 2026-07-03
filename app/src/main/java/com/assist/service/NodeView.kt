package com.assist.service

/**
 * Framework-free view over an accessibility node. Production code wraps
 * `AccessibilityNodeInfo` ([AccessibilityNodeView]); unit tests supply fakes. This
 * keeps [ScreenSerializer] and [DeviceController] logic testable on the plain JVM.
 *
 * Read-only property access is used by serialization; the `perform*` methods are
 * used by the controller to act on a node. Callers own recycling via [recycle].
 */
interface NodeView {
    val packageName: String?
    val className: String?
    val resourceId: String?
    val text: String?
    val contentDescription: String?
    val bounds: Bounds

    val isVisibleToUser: Boolean
    val isClickable: Boolean
    val isLongClickable: Boolean
    val isEditable: Boolean
    val isScrollable: Boolean
    val isCheckable: Boolean
    val isChecked: Boolean
    val isFocused: Boolean
    val isEnabled: Boolean
    val isPassword: Boolean

    val childCount: Int

    /** Returns a freshly-allocated child view, or null. Caller must [recycle] it. */
    fun child(index: Int): NodeView?

    // --- Actions (no-ops in fakes) -----------------------------------------
    fun performClick(): Boolean

    fun performLongClick(): Boolean

    fun performScrollForward(): Boolean

    fun performScrollBackward(): Boolean

    fun performSetText(text: String): Boolean

    fun performFocus(): Boolean

    fun performImeEnter(): Boolean

    /** Release the underlying native node. Safe to call once; idempotent. */
    fun recycle()
}
