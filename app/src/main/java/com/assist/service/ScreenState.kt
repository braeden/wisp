package com.assist.service

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Immutable snapshot of the foreground window's accessibility tree, produced by
 * [ScreenSerializer]. This is the perception payload handed to the LLM.
 *
 * @property appPackage package name of the foreground app (empty if unknown).
 * @property window window/activity title hint, when resolvable.
 * @property elements serialized nodes in reading (pre-order) order, capped.
 * @property truncated true if the element cap was hit and nodes were dropped.
 * @property timestampMs wall-clock time the snapshot was taken.
 */
@Serializable
data class ScreenState(
    val appPackage: String,
    val elements: List<UiElement>,
    val window: String? = null,
    val truncated: Boolean = false,
    val timestampMs: Long = 0L,
) {
    /** Machine-readable JSON rendering (kotlinx.serialization). */
    fun toJson(): String = json.encodeToString(this)

    /**
     * Compact, token-frugal outline for the LLM. One line per element:
     * `#id role "text" (desc) [flags]`.
     *
     * Pixel bounds are deliberately **omitted**: the agent addresses elements by
     * `#id` (see [ToolRouter]'s tap/scroll/setText tools), so coordinates were pure
     * token overhead — ~15–20 chars on every one of up to 150 lines per screen.
     * They remain available in [toJson] / [UiElement.bounds] for gesture geometry.
     */
    fun toOutline(): String =
        buildString {
            append("app=").append(appPackage.ifEmpty { "?" })
            window?.let { append(" window=").append(it) }
            append(" elements=").append(elements.size)
            if (truncated) append(" (truncated)")
            append('\n')
            for (e in elements) {
                append('#').append(e.id).append(' ').append(e.role)
                e.text?.takeIf { it.isNotBlank() }?.let { append(" \"").append(it).append('"') }
                e.contentDesc?.takeIf { it.isNotBlank() }?.let {
                    append(
                        " (",
                    ).append(it).append(')')
                }
                val flags =
                    buildList {
                        if (e.clickable) add("click")
                        if (e.longClickable) add("longclick")
                        if (e.editable) add("edit")
                        if (e.scrollable) add("scroll")
                        if (e.checkable) add(if (e.checked) "checked" else "checkable")
                        if (e.focused) add("focused")
                        if (e.password) add("password")
                        if (!e.enabled) add("disabled")
                    }
                if (flags.isNotEmpty()) {
                    flags.joinTo(
                        this,
                        separator = ",",
                        prefix = " [",
                        postfix = "]",
                    )
                }
                append('\n')
            }
        }

    companion object {
        val EMPTY = ScreenState(appPackage = "", elements = emptyList())

        private val json =
            Json {
                encodeDefaults = false
                prettyPrint = false
            }
    }
}
