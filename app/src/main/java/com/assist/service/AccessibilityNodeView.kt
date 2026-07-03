package com.assist.service

import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Production [NodeView] backed by a real `AccessibilityNodeInfo`. Property reads map
 * straight onto the native node; actions map to `performAction`. [recycle] releases
 * the native handle (deprecated no-op on API 33+, still honored for API 30–32).
 */
class AccessibilityNodeView(
    private val node: AccessibilityNodeInfo,
) : NodeView {
    override val packageName: String? get() = node.packageName?.toString()
    override val className: String? get() = node.className?.toString()
    override val resourceId: String? get() = node.viewIdResourceName
    override val text: String? get() = node.text?.toString()
    override val contentDescription: String? get() = node.contentDescription?.toString()

    override val bounds: Bounds
        get() {
            val r = Rect()
            node.getBoundsInScreen(r)
            return Bounds(r.left, r.top, r.right, r.bottom)
        }

    override val isVisibleToUser: Boolean get() = node.isVisibleToUser
    override val isClickable: Boolean get() = node.isClickable
    override val isLongClickable: Boolean get() = node.isLongClickable
    override val isEditable: Boolean get() = node.isEditable
    override val isScrollable: Boolean get() = node.isScrollable
    override val isCheckable: Boolean get() = node.isCheckable
    override val isChecked: Boolean get() = node.isChecked
    override val isFocused: Boolean get() = node.isFocused
    override val isEnabled: Boolean get() = node.isEnabled
    override val isPassword: Boolean get() = node.isPassword

    override val childCount: Int get() = node.childCount

    override fun child(index: Int): NodeView? =
        node.getChild(index)?.let { AccessibilityNodeView(it) }

    override fun performClick(): Boolean = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)

    override fun performLongClick(): Boolean =
        node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)

    override fun performScrollForward(): Boolean =
        node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)

    override fun performScrollBackward(): Boolean =
        node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)

    override fun performSetText(text: String): Boolean {
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args =
            Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    override fun performFocus(): Boolean = node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)

    override fun performImeEnter(): Boolean =
        node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)

    @Suppress("DEPRECATION")
    override fun recycle() {
        runCatching { node.recycle() }
    }
}
