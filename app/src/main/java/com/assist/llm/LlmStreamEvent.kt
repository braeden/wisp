package com.assist.llm

/**
 * Incremental events emitted while an assistant turn streams. The overlay
 * (phase-07) renders these live; cancelling the collecting coroutine aborts the
 * HTTP stream (interruptibility, phase-08).
 */
sealed interface LlmStreamEvent {
    /** A chunk of visible assistant text. */
    data class TextDelta(
        val text: String,
    ) : LlmStreamEvent

    /** A chunk of thinking/reasoning text (when surfaced). */
    data class ThinkingDelta(
        val text: String,
    ) : LlmStreamEvent

    /** A `tool_use` block has started; args stream via [ToolUseArgsDelta]. */
    data class ToolUseStart(
        val id: String,
        val name: String,
    ) : LlmStreamEvent

    /** A partial-JSON fragment of the current tool's arguments. */
    data class ToolUseArgsDelta(
        val id: String,
        val partialJson: String,
    ) : LlmStreamEvent

    /** Usage snapshot (may arrive mid/late stream). */
    data class UsageUpdate(
        val usage: Usage,
    ) : LlmStreamEvent

    /** Terminal event; the turn is complete with this [stopReason]. */
    data class Done(
        val stopReason: String,
    ) : LlmStreamEvent
}
