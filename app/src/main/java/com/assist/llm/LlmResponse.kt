package com.assist.llm

/**
 * The complete assistant turn. [content] is the full ordered block list (text,
 * thinking, tool_use) for faithful replay on the next request; [text] and
 * [toolCalls] are convenience projections. [stopReason] is the raw provider
 * value (e.g. "tool_use", "end_turn", "max_tokens", "refusal").
 */
data class LlmResponse(
    val text: String,
    val toolCalls: List<ToolCall>,
    val stopReason: String,
    val usage: Usage,
    val content: List<ContentBlock> = emptyList(),
    /**
     * Realized generation speed (phase-12): "fast" or "standard", mirrored from
     * [usage].speed. Null when the provider didn't report it.
     */
    val speed: String? = usage.speed,
)
