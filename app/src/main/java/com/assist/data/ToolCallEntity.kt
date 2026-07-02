package com.assist.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A single tool invocation and its outcome, recorded for observability and cost
 * attribution. [messageId] links to the assistant [MessageEntity] that emitted
 * the `tool_use` block (nullable if recorded before the message row exists).
 */
@Entity(
    tableName = "tool_calls",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId"), Index("messageId")],
)
data class ToolCallEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val messageId: Long?,
    val name: String,
    val argsJson: String,
    val resultJson: String?,
    val success: Boolean,
    val durationMs: Long,
    val createdAt: Long,
)
