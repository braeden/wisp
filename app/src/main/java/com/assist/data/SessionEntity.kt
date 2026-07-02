package com.assist.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A persisted LLM session — one conversation the user can start, resume, rename,
 * or end. Messages, tool calls, usage, media, and notes all reference it by [id].
 */
@Entity(
    tableName = "sessions",
    indices = [Index("status"), Index("updatedAt")],
)
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** Model id used by default for this session (e.g. `claude-opus-4-8`). */
    val modelDefault: String,
    /** One of [SessionStatus]. */
    val status: String,
    /** Version of the assembled system prompt this session was started with. */
    val systemPromptVersion: Int,
)
