package com.assist.agent

import com.assist.llm.ContentBlock
import com.assist.llm.ContextManagement
import com.assist.service.ScreenState

/**
 * Result of running one tool call through the [ToolRouter]. Carries the
 * `tool_result` block for the next turn plus control signals the [AgentLoop]
 * acts on (finish, context edits, whether the UI changed / a screen was
 * captured).
 */
data class ToolExecution(
    val resultBlock: ContentBlock.ToolResult,
    val success: Boolean,
    /** Short model-facing summary, for events + persisted tool-call rows. */
    val message: String,
    /** The `finish` tool was called; end the loop. */
    val finished: Boolean = false,
    val finishSummary: String? = null,
    /** A context edit to apply on the next request (drop screenshots / compact). */
    val contextEdit: ContextManagement? = null,
    /** A gesture/control action ran that likely changed the UI (await settle). */
    val didAct: Boolean = false,
    /** This call already returned screen state/screenshot (skip auto-perception). */
    val producedPerception: Boolean = false,
    /** Fresh screen state to refresh the loop's cache, when captured. */
    val screenState: ScreenState? = null,
)
