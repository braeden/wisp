package com.assist.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenStateRenderingTest {
    private val state =
        ScreenState(
            appPackage = "com.example.app",
            window = "MainActivity",
            elements =
                listOf(
                    UiElement(
                        id = 0,
                        role = "Button",
                        text = "Send",
                        bounds = Bounds(10, 20, 110, 80),
                        clickable = true,
                    ),
                    UiElement(
                        id = 1,
                        role = "EditText",
                        contentDesc = "Message",
                        bounds = Bounds(0, 100, 400, 160),
                        editable = true,
                        focused = true,
                    ),
                ),
        )

    @Test
    fun `outline includes ids roles and flags but omits bounds`() {
        val outline = state.toOutline()
        assertTrue(outline.contains("app=com.example.app"))
        assertTrue(outline.contains("window=MainActivity"))
        assertTrue(outline.contains("#0 Button \"Send\""))
        assertTrue(outline.contains("[click]"))
        assertTrue(outline.contains("#1 EditText"))
        assertTrue(outline.contains("(Message)"))
        assertTrue(outline.contains("[edit,focused]"))
        // Bounds are intentionally dropped from the outline (token savings) — the
        // agent addresses elements by #id, not coordinates.
        assertFalse(outline.contains("@10,20-110,80"))
    }

    @Test
    fun `json round-trips element ids`() {
        val json = state.toJson()
        assertTrue(json.contains("\"appPackage\":\"com.example.app\""))
        assertTrue(json.contains("\"id\":0"))
        assertTrue(json.contains("\"id\":1"))
    }
}
