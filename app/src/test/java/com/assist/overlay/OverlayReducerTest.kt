package com.assist.overlay

import com.assist.agent.AgentEvent
import com.assist.data.ContextStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayReducerTest {

    private val reducer = OverlayReducer()

    private fun reduce(
        start: OverlayUiState = OverlayUiState.INITIAL,
        vararg events: AgentEvent,
    ): OverlayUiState =
        events.fold(start) { state, event -> reducer.reduce(state, OverlayInput.Event(event)) }

    @Test
    fun `started sets session, intent, and thinking phase and clears transcript`() {
        val dirty = OverlayUiState.INITIAL.copy(
            assistantText = "old",
            toolChips = listOf(ToolChip("t", "tap", "{}", ToolStatus.SUCCESS)),
            finished = true,
            expanded = true,
        )
        val state = reducer.reduce(dirty, OverlayInput.Event(AgentEvent.Started(7L, "do a thing")))

        assertEquals(AgentPhase.THINKING, state.phase)
        assertEquals(7L, state.sessionId)
        assertEquals("do a thing", state.intent)
        assertEquals("", state.assistantText)
        assertTrue(state.toolChips.isEmpty())
        assertFalse(state.finished)
        // UI-local expand is preserved across a fresh run.
        assertTrue(state.expanded)
    }

    @Test
    fun `assistant text deltas accumulate in order`() {
        val state = reduce(
            events = arrayOf(
                AgentEvent.AssistantText("Hel"),
                AgentEvent.AssistantText("lo, "),
                AgentEvent.AssistantText("world"),
            ),
        )
        assertEquals("Hello, world", state.assistantText)
        assertEquals(AgentPhase.SPEAKING, state.phase)
    }

    @Test
    fun `thinking sets flag and assistant text clears it`() {
        val thinking = reduce(events = arrayOf(AgentEvent.Thinking("hmm")))
        assertTrue(thinking.isThinking)
        assertEquals("hmm", thinking.thinking)

        val after = reducer.reduce(thinking, OverlayInput.Event(AgentEvent.AssistantText("answer")))
        assertFalse(after.isThinking)
    }

    @Test
    fun `tool chips preserve emission order and update by id`() {
        val state = reduce(
            events = arrayOf(
                AgentEvent.ToolCallStarted("a", "open_app", """{"name":"Clock"}"""),
                AgentEvent.ToolCallStarted("b", "tap", """{"element_id":3}"""),
                AgentEvent.ToolCallFinished("a", "open_app", success = true, message = "opened"),
                AgentEvent.ToolCallFinished("b", "tap", success = false, message = "no such element"),
            ),
        )
        assertEquals(listOf("a", "b"), state.toolChips.map { it.id })
        assertEquals(ToolStatus.SUCCESS, state.toolChips[0].status)
        assertEquals("opened", state.toolChips[0].result)
        assertEquals(ToolStatus.FAILURE, state.toolChips[1].status)
        assertEquals("no such element", state.toolChips[1].result)
        assertEquals(AgentPhase.ACTING, state.phase)
    }

    @Test
    fun `awaiting confirmation shows prompt and finished tool clears it`() {
        val awaiting = reduce(
            events = arrayOf(
                AgentEvent.ToolCallStarted("c", "tap", "{}"),
                AgentEvent.AwaitingConfirmation("Confirm send?", "SEND"),
            ),
        )
        assertEquals(AgentPhase.LISTENING, awaiting.phase)
        assertEquals("Confirm send?", awaiting.confirmation?.question)
        assertEquals("SEND", awaiting.confirmation?.category)

        val resolved = reducer.reduce(
            awaiting,
            OverlayInput.Event(AgentEvent.ToolCallFinished("c", "tap", success = true, message = "sent")),
        )
        assertNull(resolved.confirmation)
    }

    @Test
    fun `finished sets summary and idle phase`() {
        val state = reduce(events = arrayOf(AgentEvent.Finished("all done")))
        assertTrue(state.finished)
        assertEquals("all done", state.summary)
        assertEquals(AgentPhase.IDLE, state.phase)
    }

    @Test
    fun `error surfaces message and idles`() {
        val state = reduce(events = arrayOf(AgentEvent.Error("boom")))
        assertEquals("boom", state.error)
        assertEquals(AgentPhase.IDLE, state.phase)
    }

    @Test
    fun `hud input projects context status with clamped fraction`() {
        val status = ContextStatus(usedTokens = 250_000, windowTokens = 1_000_000, costUsd = 0.1234, screenshotCount = 2)
        val state = reducer.reduce(OverlayUiState.INITIAL, OverlayInput.Hud(status))
        assertEquals(250_000, state.hud?.usedTokens)
        assertEquals(0.25f, state.hud?.contextFraction!!, 0.0001f)
        assertEquals(2, state.hud?.screenshotCount)
    }

    @Test
    fun `hud fraction is clamped to one when used exceeds window`() {
        val over = HudState(usedTokens = 300, windowTokens = 100, costUsd = 0.0, screenshotCount = 0)
        assertEquals(1f, over.contextFraction, 0.0f)
        val zeroWindow = HudState(usedTokens = 10, windowTokens = 0, costUsd = 0.0, screenshotCount = 0)
        assertEquals(0f, zeroWindow.contextFraction, 0.0f)
    }

    @Test
    fun `expand input flips only the expanded flag`() {
        val state = reducer.reduce(
            OverlayUiState.INITIAL.copy(assistantText = "keep"),
            OverlayInput.Expand(true),
        )
        assertTrue(state.expanded)
        assertEquals("keep", state.assistantText)
    }
}
