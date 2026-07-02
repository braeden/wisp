package com.assist.llm

/**
 * Conversation role for a turn sent to / received from the model.
 *
 * [SYSTEM] is a **mid-conversation system turn** (phase-12): operator-level
 * steering (barge-in relay, budget/mode changes) appended into the `messages`
 * array. Strict placement rules apply — see
 * [com.assist.agent.SystemTurnPlacement]. System turns are transient operator
 * context, never untrusted input, and never persisted as user/assistant history.
 */
enum class Role { USER, ASSISTANT, SYSTEM }

/**
 * One conversation turn. `tool_result` blocks live inside a [Role.USER] message's
 * [content] (Anthropic convention); assistant `tool_use` blocks live inside a
 * [Role.ASSISTANT] message. Phase-05 reconstructs `List<LlmMessage>` from the DB.
 */
data class LlmMessage(
    val role: Role,
    val content: List<ContentBlock>,
)
