package com.assist.agent

import com.assist.llm.ContentBlock
import com.assist.llm.LlmMessage
import com.assist.llm.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemTurnPlacementTest {
    private fun msg(
        role: Role,
        text: String = "x",
    ) = LlmMessage(role = role, content = listOf(ContentBlock.Text(text)))

    @Test
    fun `cannot append to an empty conversation (never first)`() {
        assertFalse(SystemTurnPlacement.canAppend(emptyList()))
    }

    @Test
    fun `can append after a user turn`() {
        assertTrue(SystemTurnPlacement.canAppend(listOf(msg(Role.USER))))
    }

    @Test
    fun `can append after a user turn carrying tool_result`() {
        val toolResult =
            LlmMessage(
                role = Role.USER,
                content =
                    listOf(
                        ContentBlock.ToolResult(
                            toolUseId = "t1",
                            content = listOf(ContentBlock.Text("ok")),
                        ),
                    ),
            )
        assertTrue(
            SystemTurnPlacement.canAppend(listOf(msg(Role.USER), msg(Role.ASSISTANT), toolResult)),
        )
    }

    @Test
    fun `cannot append after an assistant turn (would split tool_use from tool_result)`() {
        assertFalse(SystemTurnPlacement.canAppend(listOf(msg(Role.USER), msg(Role.ASSISTANT))))
    }

    @Test
    fun `cannot append after a system turn (no consecutive system turns)`() {
        assertFalse(SystemTurnPlacement.canAppend(listOf(msg(Role.USER), msg(Role.SYSTEM))))
    }

    @Test
    fun `append adds a system turn at the end`() {
        val base = listOf(msg(Role.USER, "hello"))
        val result = SystemTurnPlacement.append(base, "New input arrived from the user: stop")
        assertEquals(2, result.size)
        assertEquals(Role.SYSTEM, result.last().role)
        assertEquals(
            "New input arrived from the user: stop",
            (result.last().content.single() as ContentBlock.Text).text,
        )
    }

    @Test(expected = IllegalStateException::class)
    fun `append at an illegal position throws`() {
        SystemTurnPlacement.append(listOf(msg(Role.ASSISTANT)), "nope")
    }
}
