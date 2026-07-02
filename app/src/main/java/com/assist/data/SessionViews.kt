package com.assist.data

import androidx.room.Embedded

/**
 * Read models for the session-management UI (phase-12). These are decoded,
 * framework-free projections the view-model reducers operate on, so the reducers
 * can be unit-tested with fabricated instances (no Room, no Android).
 */

/** A session row plus its message count and running cost, from one DAO query. */
data class SessionSummary(
    @Embedded val session: SessionEntity,
    val messageCount: Int,
    val costUsd: Double,
)

/** Role of a transcript turn as rendered in the detail screen. */
enum class TranscriptRole { USER, ASSISTANT, SYSTEM, SYSTEM_NOTE, TOOL_RESULT }

/** A single content block in a decoded transcript message. */
sealed interface TranscriptBlock {
    data class Text(val text: String) : TranscriptBlock
    data class Thinking(val text: String) : TranscriptBlock
    data class ToolUse(val id: String, val name: String, val argsJson: String) : TranscriptBlock
    data class ToolResult(val toolUseId: String, val text: String, val isError: Boolean) : TranscriptBlock

    /** A screenshot reference; [dropped] once it has been evicted from context. */
    data class Image(val dropped: Boolean) : TranscriptBlock
}

/** One decoded message in a session transcript. */
data class TranscriptMessage(
    val id: Long,
    val seq: Int,
    val role: TranscriptRole,
    val kind: String,
    val createdAt: Long,
    val blocks: List<TranscriptBlock>,
)

/** Per-model token/cost totals for a session. */
data class ModelUsage(
    val model: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val cacheReadTokens: Int,
    val cacheWriteTokens: Int,
    val costUsd: Double,
    val turns: Int,
)

/** Aggregate token/cost accounting for a session, broken down per model. */
data class UsageSummary(
    val totalCostUsd: Double,
    val totalInputTokens: Int,
    val totalOutputTokens: Int,
    val totalCacheReadTokens: Int,
    val totalCacheWriteTokens: Int,
    val perModel: List<ModelUsage>,
) {
    companion object {
        val EMPTY = UsageSummary(0.0, 0, 0, 0, 0, emptyList())
    }
}
