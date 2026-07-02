package com.assist.prompt

import com.assist.llm.SystemBlock

/**
 * Produces the system prompt that prefixes every session as the cacheable stable
 * prefix for `LlmRequest.system`.
 *
 * The returned list is always ordered `[stable core, dynamic tail]`:
 * - the **stable core** is marked [SystemBlock.cacheable] so the Anthropic impl
 *   places the `cache_control` breakpoint on it (caches tools + system up to that
 *   point). It is byte-stable for a given [version], so repeated turns hit the
 *   prompt cache.
 * - the **dynamic tail** (device info, session notes, wall-clock time) is emitted
 *   *after* the breakpoint and is never cacheable, so volatile context does not
 *   invalidate the cache.
 *
 * Phase-06's `AgentLoop` consumes this. The contract is intentionally minimal: a
 * single [system] call, with an optional [PromptContext] whose fields all default,
 * so `provider.system()` is valid and `provider.system(PromptContext(...))` adds
 * the live tail.
 */
interface SystemPromptProvider {

    /** Monotonic version of the stable core; record per session (see phase-05). */
    val version: Int

    /**
     * Build the system blocks for a turn. [context] supplies the dynamic tail;
     * omit it (or pass an empty [PromptContext]) to get just the stable core plus
     * an empty tail.
     */
    fun system(context: PromptContext = PromptContext()): List<SystemBlock>

    companion object {
        /**
         * Bump when the stable core text changes so sessions can record which
         * prompt they ran under and the cache is not silently reused across edits.
         */
        const val PROMPT_VERSION: Int = 1
    }
}

/**
 * Volatile context appended after the cache breakpoint. Every field defaults so
 * callers can supply only what they have; anything left null/blank is omitted from
 * the rendered tail (keeping it small and drift-free).
 */
data class PromptContext(
    /** Device marketing/model name, e.g. "Pixel 7 Pro". */
    val deviceModel: String? = null,
    /** Android release, e.g. "Android 15". */
    val androidVersion: String? = null,
    /** Screen size in px, e.g. "1440x3120". */
    val screenSize: String? = null,
    /** BCP-47 locale tag, e.g. "en-US". */
    val locale: String? = null,
    /** Current wall-clock time as an already-formatted, localized string. */
    val currentTime: String? = null,
    /** Durable session notes carried across compaction (see phase-05 `note`). */
    val sessionNotes: List<String> = emptyList(),
    /** Hints about notable installed apps (label / package), best-effort. */
    val installedAppHints: List<String> = emptyList(),
)
