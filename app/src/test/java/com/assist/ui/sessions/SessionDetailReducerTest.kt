package com.assist.ui.sessions

import com.assist.data.ContextStatus
import com.assist.data.ModelUsage
import com.assist.data.SessionEntity
import com.assist.data.SessionStatus
import com.assist.data.ToolCallEntity
import com.assist.data.TranscriptBlock
import com.assist.data.TranscriptMessage
import com.assist.data.TranscriptRole
import com.assist.data.UsageSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionDetailReducerTest {

    private val session = SessionEntity(
        id = 7,
        title = "Open clock",
        createdAt = 1,
        updatedAt = 2,
        modelDefault = "claude-opus-4-8",
        status = SessionStatus.ACTIVE,
        systemPromptVersion = 1,
    )

    private fun msg(id: Long, seq: Int, role: TranscriptRole, blocks: List<TranscriptBlock>) =
        TranscriptMessage(id = id, seq = seq, role = role, kind = "message", createdAt = seq.toLong(), blocks = blocks)

    @Test
    fun `pairs tool_use with its result and enriches from tool-call records`() {
        val transcript = listOf(
            msg(1, 0, TranscriptRole.USER, listOf(TranscriptBlock.Text("open the clock"))),
            msg(
                2, 1, TranscriptRole.ASSISTANT,
                listOf(
                    TranscriptBlock.Thinking("planning"),
                    TranscriptBlock.ToolUse("tu_1", "open_app", """{"app":"clock"}"""),
                ),
            ),
            msg(
                3, 2, TranscriptRole.TOOL_RESULT,
                listOf(
                    TranscriptBlock.ToolResult("tu_1", "opened clock", isError = false),
                    TranscriptBlock.Image(dropped = false),
                ),
            ),
        )
        val toolCalls = listOf(
            ToolCallEntity(
                id = 1, sessionId = 7, messageId = null, name = "open_app",
                argsJson = """{"app":"clock"}""", resultJson = "opened clock",
                success = true, durationMs = 42, createdAt = 1,
            ),
        )

        val state = SessionDetailReducer.reduce(
            session = session,
            transcript = transcript,
            toolCalls = toolCalls,
            usage = UsageSummary(
                totalCostUsd = 0.02, totalInputTokens = 1000, totalOutputTokens = 500,
                totalCacheReadTokens = 0, totalCacheWriteTokens = 0,
                perModel = listOf(ModelUsage("claude-opus-4-8", 1000, 500, 0, 0, 0.02, 1)),
            ),
            context = ContextStatus(usedTokens = 1602, windowTokens = 1_000_000, costUsd = 0.02, screenshotCount = 1),
        )

        assertEquals("Open clock", state.title)
        assertEquals("claude-opus-4-8", state.model)

        val message = state.items.filterIsInstance<TranscriptItemUi.Message>().first()
        assertEquals(TranscriptRole.USER, message.role)
        assertEquals("open the clock", message.text)

        val thinking = state.items.filterIsInstance<TranscriptItemUi.Thinking>().single()
        assertTrue(thinking.preview.contains("planning"))

        val chip = state.items.filterIsInstance<TranscriptItemUi.ToolChip>().single()
        assertEquals("open_app", chip.name)
        assertEquals("opened clock", chip.result)
        assertTrue(chip.ok)
        assertEquals(42L, chip.durationMs)

        val shot = state.items.filterIsInstance<TranscriptItemUi.Screenshot>().single()
        assertEquals(false, shot.dropped)

        // Context panel reflects the status + per-model usage.
        assertEquals(1602, state.context!!.usedTokens)
        assertEquals(1_000_000, state.context!!.windowTokens)
        assertEquals(1, state.context!!.screenshotCount)
        assertEquals(1, state.context!!.perModel.size)
    }

    @Test
    fun `failed tool result yields a not-ok chip`() {
        val transcript = listOf(
            msg(1, 0, TranscriptRole.ASSISTANT, listOf(TranscriptBlock.ToolUse("tu_1", "tap", """{"element_id":5}"""))),
            msg(2, 1, TranscriptRole.TOOL_RESULT, listOf(TranscriptBlock.ToolResult("tu_1", "no such element", isError = true))),
        )
        val state = SessionDetailReducer.reduce(session, transcript, emptyList(), UsageSummary.EMPTY, null)
        val chip = state.items.filterIsInstance<TranscriptItemUi.ToolChip>().single()
        assertEquals(false, chip.ok)
        assertEquals("no such element", chip.result)
    }

    @Test
    fun `null session and empty usage degrade cleanly`() {
        val state = SessionDetailReducer.reduce(null, emptyList(), emptyList(), UsageSummary.EMPTY, null)
        assertEquals("Session", state.title)
        assertTrue(state.items.isEmpty())
        assertEquals(0, state.context!!.usedTokens)
    }
}
