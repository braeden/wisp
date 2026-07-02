package com.assist.ui.sessions

import com.assist.data.SessionEntity
import com.assist.data.SessionStatus
import com.assist.data.SessionSummary
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionsReducerTest {

    private fun summary(id: Long, title: String, cost: Double, msgs: Int, updated: Long) =
        SessionSummary(
            session = SessionEntity(
                id = id,
                title = title,
                createdAt = 1,
                updatedAt = updated,
                modelDefault = "claude-opus-4-8",
                status = SessionStatus.ACTIVE,
                systemPromptVersion = 1,
            ),
            messageCount = msgs,
            costUsd = cost,
        )

    @Test
    fun `maps summaries to rows preserving order and fields`() {
        val state = SessionsReducer.reduce(
            listOf(
                summary(1, "Open clock", 0.0175, 6, 200),
                summary(2, "Find flight", 0.5, 12, 100),
            ),
        )
        assertEquals(false, state.loading)
        assertEquals(2, state.rows.size)
        assertEquals(1L, state.rows[0].id)
        assertEquals("Open clock", state.rows[0].title)
        assertEquals(0.0175, state.rows[0].costUsd, 1e-9)
        assertEquals(6, state.rows[0].messageCount)
        assertEquals(SessionStatus.ACTIVE, state.rows[0].status)
    }

    @Test
    fun `blank titles fall back to Untitled`() {
        val state = SessionsReducer.reduce(listOf(summary(1, "   ", 0.0, 0, 1)))
        assertEquals("Untitled session", state.rows.single().title)
    }
}
