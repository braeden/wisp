package com.assist.agent

import com.assist.llm.Usage

/**
 * Everything the agent loop emits on the [AgentEventBus]. The overlay (phase-07)
 * renders these live; voice (phase-08) subscribes for speech + barge-in.
 * Model-agnostic — carries no Anthropic types.
 */
sealed interface AgentEvent {
    /** A run has begun for [sessionId] with the user's [intent]. */
    data class Started(
        val sessionId: Long,
        val intent: String,
    ) : AgentEvent

    /** A chunk of visible assistant text as it streams. */
    data class AssistantText(
        val text: String,
    ) : AgentEvent

    /** A chunk of the model's reasoning (when surfaced). */
    data class Thinking(
        val text: String,
    ) : AgentEvent

    /** A tool is about to execute. [argsJson] is the raw arguments object. */
    data class ToolCallStarted(
        val id: String,
        val name: String,
        val argsJson: String,
    ) : AgentEvent

    /** A tool finished. [message] is the model-facing result text. */
    data class ToolCallFinished(
        val id: String,
        val name: String,
        val success: Boolean,
        val message: String,
    ) : AgentEvent

    /**
     * A gated (sensitive/irreversible) action is awaiting user confirmation.
     * [category] is the [ActionGate] classification (e.g. "SEND", "DELETE").
     */
    data class AwaitingConfirmation(
        val question: String,
        val category: String,
    ) : AgentEvent

    /** The loop is paused for user input (after an interrupt, or a blocking `ask`). */
    data object Listening : AgentEvent

    /** The agent is speaking [text] (drives TTS in phase-08; shown in the overlay). */
    data class Speaking(
        val text: String,
    ) : AgentEvent

    /** Fresh token usage after a turn. */
    data class UsageUpdated(
        val usage: Usage,
    ) : AgentEvent

    /** A recoverable/terminal error occurred. */
    data class Error(
        val message: String,
    ) : AgentEvent

    /** The task finished. [summary] is the agent's closing summary, if any. */
    data class Finished(
        val summary: String?,
    ) : AgentEvent
}
