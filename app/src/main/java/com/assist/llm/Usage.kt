package com.assist.llm

/**
 * Token usage for one turn. Cache counts let phase-05 verify prompt caching is
 * working (a repeated identical prefix should report non-zero [cacheReadTokens])
 * and price turns accurately.
 */
data class Usage(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val cacheReadTokens: Int = 0,
    val cacheWriteTokens: Int = 0,
    /**
     * Realized generation speed (phase-12): "fast" or "standard", from the
     * response's `usage.speed`. Null when the provider didn't report it. Recorded
     * so cost accounting can price fast usage at the premium multiplier.
     */
    val speed: String? = null,
)
