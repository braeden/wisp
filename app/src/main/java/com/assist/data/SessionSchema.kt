package com.assist.data

/**
 * Shared string constants for the session Room schema. Kept as plain constants
 * (not enums) so they serialize as stable strings in DB columns and survive
 * refactors without a migration.
 */
object MessageRole {
    /** A user turn (also the carrier for `tool_result` blocks, per Anthropic convention). */
    const val USER = "user"

    /** An assistant turn (may carry `tool_use` / thinking blocks). */
    const val ASSISTANT = "assistant"

    /** A tool-result turn — stored as a USER-role message; distinguished by [MessageKind.TOOL_RESULT]. */
    const val TOOL_RESULT = "tool_result"

    /** A durable system note injected into history (e.g. a compaction summary). */
    const val SYSTEM_NOTE = "system-note"
}

/** Coarse classification of a [MessageEntity], orthogonal to its role. */
object MessageKind {
    const val MESSAGE = "message"
    const val TOOL_RESULT = "tool_result"
    const val SUMMARY = "summary"
}

/** Lifecycle status of a [SessionEntity]. */
object SessionStatus {
    const val ACTIVE = "active"
    const val ENDED = "ended"
}

/** Kind of a [MediaEntity] file on disk. */
object MediaKind {
    const val SCREENSHOT = "screenshot"
}
