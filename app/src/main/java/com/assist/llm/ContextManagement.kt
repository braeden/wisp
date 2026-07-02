package com.assist.llm

/**
 * Model-agnostic request for in-turn context management. The Anthropic impl maps
 * this to `context_management.edits` (context editing) and/or compaction on the
 * same messages request, with the matching beta headers. Kept generic so nothing
 * provider-specific leaks into the seam.
 *
 * Backs the agent's `drop_old_screenshots` / `compact_conversation` tools
 * (see ARCHITECTURE.md "Context/economy").
 */
data class ContextManagement(
    /** Clear old tool-use/tool-result blocks (stale screenshots) from context. */
    val clearToolUses: Boolean = false,
    /** When clearing, keep this many most-recent tool uses (null = provider default). */
    val keepLastToolUses: Int? = null,
    /** Request server-side compaction (summarize earlier history). */
    val compact: Boolean = false,
) {
    /** True if this carries any actionable edit. */
    val isEmpty: Boolean
        get() = !clearToolUses && keepLastToolUses == null && !compact
}
