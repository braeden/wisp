package com.assist.llm

/**
 * A piece of message content, model-agnostic. The Anthropic impl
 * (`llm/anthropic/`) maps these to/from wire JSON; nothing here is Anthropic-
 * specific. Screenshots travel as [Image] (base64); tool executions travel as
 * [ToolUse] (assistant → app) and [ToolResult] (app → assistant).
 */
sealed interface ContentBlock {
    /** Plain text. */
    data class Text(
        val text: String,
    ) : ContentBlock

    /** An image, base64-encoded (e.g. a screenshot). [mediaType] like "image/png". */
    data class Image(
        val base64: String,
        val mediaType: String = "image/png",
    ) : ContentBlock

    /** Model's private reasoning; replayed verbatim on the same model. */
    data class Thinking(
        val text: String,
        val signature: String? = null,
    ) : ContentBlock

    /** A tool the assistant wants executed. [inputJson] is the raw JSON arguments object. */
    data class ToolUse(
        val id: String,
        val name: String,
        val inputJson: String,
    ) : ContentBlock

    /**
     * The result of executing a [ToolUse], sent back on the next user turn.
     * May carry text and/or images (e.g. a screenshot after a tap).
     */
    data class ToolResult(
        val toolUseId: String,
        val content: List<ContentBlock>,
        val isError: Boolean = false,
    ) : ContentBlock
}
