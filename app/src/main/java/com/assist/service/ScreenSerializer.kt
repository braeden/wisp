package com.assist.service

/**
 * Walks an accessibility node tree ([NodeView]) into a compact [ScreenState] and a
 * live `id -> node` map ([ScreenFrame]).
 *
 * Token/ANR controls:
 * - **Element cap** ([maxElements], default 150): once reached, remaining
 *   meaningful nodes are dropped and [ScreenState.truncated] is set.
 * - **Depth cap** ([maxDepth]): stops descending very deep trees.
 * - **Text cap** ([maxTextLength]): truncates long labels.
 *
 * Recycling contract: `serialize` takes ownership of `root` and every descendant it
 * allocates. Nodes retained in the returned frame's map are recycled by
 * [ScreenFrame.recycle]; every other visited node is recycled immediately. Callers
 * must NOT recycle `root` themselves after handing it in.
 */
class ScreenSerializer(
    private val maxElements: Int = 150,
    private val maxDepth: Int = 40,
    private val maxTextLength: Int = 200,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun serialize(root: NodeView?): ScreenFrame {
        if (root ==
            null
        ) {
            return ScreenFrame(ScreenState.EMPTY.copy(timestampMs = clock()), emptyMap())
        }

        val elements = ArrayList<UiElement>()
        val nodes = HashMap<Int, NodeView>()
        val ctx = Walk(elements, nodes)
        walk(root, 0, ctx)

        val state =
            ScreenState(
                appPackage = root.packageName?.toString().orEmpty(),
                elements = elements,
                window = simpleName(root.className),
                truncated = ctx.truncated,
                timestampMs = clock(),
            )
        return ScreenFrame(state, nodes)
    }

    private class Walk(
        val elements: ArrayList<UiElement>,
        val nodes: HashMap<Int, NodeView>,
    ) {
        var nextId = 0
        var truncated = false
    }

    private fun walk(
        node: NodeView,
        depth: Int,
        ctx: Walk,
    ) {
        var retained = false
        if (ctx.elements.size < maxElements) {
            if (isMeaningful(node)) {
                val id = ctx.nextId++
                ctx.elements.add(toElement(id, node))
                ctx.nodes[id] = node
                retained = true
            }
        } else {
            // Cap reached; note truncation only if we'd otherwise have added.
            if (isMeaningful(node)) ctx.truncated = true
        }

        if (depth < maxDepth) {
            val count = node.childCount
            for (i in 0 until count) {
                val child = node.child(i) ?: continue
                walk(child, depth + 1, ctx)
            }
        }

        if (!retained) runCatching { node.recycle() }
    }

    private fun isMeaningful(node: NodeView): Boolean {
        if (!node.isVisibleToUser) return false
        val hasText = !node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank()
        val actionable =
            node.isClickable ||
                node.isLongClickable ||
                node.isEditable ||
                node.isScrollable ||
                node.isCheckable
        return hasText || actionable
    }

    private fun toElement(
        id: Int,
        node: NodeView,
    ): UiElement =
        UiElement(
            id = id,
            role = simpleName(node.className) ?: "View",
            text = clip(node.text),
            contentDesc = clip(node.contentDescription),
            resourceId = node.resourceId?.substringAfterLast('/')?.takeIf { it.isNotBlank() },
            bounds = node.bounds,
            clickable = node.isClickable,
            longClickable = node.isLongClickable,
            editable = node.isEditable,
            scrollable = node.isScrollable,
            checkable = node.isCheckable,
            checked = node.isChecked,
            focused = node.isFocused,
            password = node.isPassword,
            enabled = node.isEnabled,
        )

    private fun clip(raw: String?): String? {
        val s = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return if (s.length <= maxTextLength) s else s.take(maxTextLength) + "…"
    }

    /** `android.widget.Button` -> `Button`; null/blank -> null. */
    private fun simpleName(className: String?): String? =
        className?.substringAfterLast('.')?.takeIf { it.isNotBlank() }
}
