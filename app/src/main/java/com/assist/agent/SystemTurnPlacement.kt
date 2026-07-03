package com.assist.agent

import com.assist.llm.ContentBlock
import com.assist.llm.LlmMessage
import com.assist.llm.Role

/**
 * Placement-legality for **mid-conversation system turns** (phase-12 §5 hooks).
 * A `{"role":"system"}` turn is operator-level steering (barge-in relay, budget/
 * mode changes) appended into the `messages` array. Anthropic enforces strict
 * placement (400 if violated); this checks it locally before we send.
 *
 * Legal iff the list is **non-empty** and the **last turn is a user turn** (a
 * plain user message or one carrying `tool_result` blocks). That single rule
 * covers all the documented constraints:
 *  - never first (list must be non-empty),
 *  - must immediately follow a user / tool_result turn (last is USER),
 *  - never between a `tool_use` and its `tool_result` (an assistant-last turn,
 *    which may hold a pending tool_use, is rejected),
 *  - no two consecutive system turns (a system-last turn is rejected).
 *
 * This phase only ships the check + a safe [append]; the `SessionSteering`
 * barge-in/budget ergonomics and persistence are a follow-on.
 */
object SystemTurnPlacement {
    /** True if a system turn may be legally appended after [messages]. */
    fun canAppend(messages: List<LlmMessage>): Boolean =
        messages.isNotEmpty() && messages.last().role == Role.USER

    /**
     * Return [messages] with a system turn carrying [text] appended, or throw
     * [IllegalStateException] if the position is illegal (guard misuse in tests).
     */
    fun append(
        messages: List<LlmMessage>,
        text: String,
    ): List<LlmMessage> {
        check(canAppend(messages)) {
            "Illegal system-turn placement: must follow a user/tool_result turn (was " +
                "${messages.lastOrNull()?.role ?: "empty"})."
        }
        return messages +
            LlmMessage(
                role = Role.SYSTEM,
                content = listOf(ContentBlock.Text(text)),
            )
    }
}
