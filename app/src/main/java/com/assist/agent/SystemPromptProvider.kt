package com.assist.agent

import com.assist.llm.SystemBlock

/**
 * Assembles the system prompt for a turn. **Owned by phase-10** — the agent loop
 * consumes it behind this interface and phase-10 swaps its concrete impl in at
 * merge. Phase-06 ships [PlaceholderSystemPromptProvider].
 *
 * The returned blocks form the cacheable stable prefix, so [system] must be
 * **deterministic for a stable [SystemPromptContext]** across the turns of one
 * session (varying it per turn busts the prompt cache). Mark the last block
 * [SystemBlock.cacheable] = true.
 */
interface SystemPromptProvider {
    fun system(context: SystemPromptContext): List<SystemBlock>
}

/**
 * Inputs available to the system-prompt builder. Additive: phase-10 may read more
 * fields as they are added here without changing the agent loop.
 *
 * @property sessionId the active session.
 * @property userIntent the task the user asked for (stable for the session).
 * @property toolNames names of the tools advertised this turn (the action space).
 * @property deviceInfo optional short device descriptor (model / screen), or null.
 */
data class SystemPromptContext(
    val sessionId: Long,
    val userIntent: String,
    val toolNames: List<String>,
    val deviceInfo: String? = null,
)
