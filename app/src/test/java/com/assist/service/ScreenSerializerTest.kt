package com.assist.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenSerializerTest {
    private val serializer = ScreenSerializer(clock = { 123L })

    @Test
    fun `maps node fields into UiElement`() {
        val button =
            FakeNodeView(
                className = "android.widget.Button",
                text = "Send",
                resourceId = "com.example.app:id/send_btn",
                bounds = Bounds(10, 20, 110, 80),
                isClickable = true,
                isFocused = true,
            )
        val root = FakeNodeView(className = "android.widget.FrameLayout", children = listOf(button))

        val frame = serializer.serialize(root)
        val el = frame.state.elements.single()

        assertEquals(0, el.id)
        assertEquals("Button", el.role)
        assertEquals("Send", el.text)
        assertEquals("send_btn", el.resourceId)
        assertEquals(Bounds(10, 20, 110, 80), el.bounds)
        assertTrue(el.clickable)
        assertTrue(el.focused)
        assertEquals("com.example.app", frame.state.appPackage)
    }

    @Test
    fun `skips non-meaningful containers but keeps their meaningful children`() {
        val label = FakeNodeView(className = "android.widget.TextView", text = "Hello")
        // Container: no text, not actionable -> not serialized, but traversed.
        val container =
            FakeNodeView(className = "android.widget.LinearLayout", children = listOf(label))
        val root =
            FakeNodeView(className = "android.widget.FrameLayout", children = listOf(container))

        val frame = serializer.serialize(root)

        assertEquals(1, frame.state.elements.size)
        assertEquals(
            "Hello",
            frame.state.elements
                .single()
                .text,
        )
    }

    @Test
    fun `skips invisible nodes`() {
        val hidden = FakeNodeView(text = "hidden", isVisibleToUser = false)
        val root = FakeNodeView(children = listOf(hidden))

        val frame = serializer.serialize(root)

        assertTrue(frame.state.elements.isEmpty())
    }

    @Test
    fun `assigns stable ids that resolve back to nodes`() {
        val a = FakeNodeView(text = "A", isClickable = true)
        val b = FakeNodeView(text = "B", isClickable = true)
        val root = FakeNodeView(children = listOf(a, b))

        val frame = serializer.serialize(root)

        assertEquals(listOf(0, 1), frame.state.elements.map { it.id })
        assertTrue(frame.node(0) === a)
        assertTrue(frame.node(1) === b)
        assertNull(frame.node(99))
    }

    @Test
    fun `caps element count and marks truncated`() {
        val kids = (1..200).map { FakeNodeView(text = "item $it", isClickable = true) }
        val root = FakeNodeView(children = kids)
        val capped = ScreenSerializer(maxElements = 150, clock = { 0L })

        val frame = capped.serialize(root)

        assertEquals(150, frame.state.elements.size)
        assertTrue(frame.state.truncated)
    }

    @Test
    fun `truncates long text`() {
        val long = "x".repeat(500)
        val node = FakeNodeView(text = long, isClickable = true)
        val root = FakeNodeView(children = listOf(node))
        val s = ScreenSerializer(maxTextLength = 200, clock = { 0L })

        val el =
            s
                .serialize(root)
                .state.elements
                .single()

        assertEquals(201, el.text!!.length) // 200 chars + ellipsis
        assertTrue(el.text!!.endsWith("…"))
    }

    @Test
    fun `recycles skipped nodes but retains mapped nodes until frame recycle`() {
        val kept = FakeNodeView(text = "keep", isClickable = true)
        val skipped = FakeNodeView(className = "android.widget.LinearLayout") // no text/action
        val root =
            FakeNodeView(className = "android.widget.FrameLayout", children = listOf(kept, skipped))

        val frame = serializer.serialize(root)

        assertTrue("skipped node recycled immediately", skipped.recycled)
        assertTrue("root recycled immediately", root.recycled)
        assertFalse("kept node not yet recycled", kept.recycled)

        frame.recycle()
        assertTrue("kept node recycled on frame.recycle", kept.recycled)
    }

    @Test
    fun `null root yields empty state`() {
        val frame = serializer.serialize(null)
        assertEquals(ScreenState.EMPTY.elements, frame.state.elements)
        assertEquals("", frame.state.appPackage)
    }
}
