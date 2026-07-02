package com.assist.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One turn in a session. [contentJson] holds a serialized `List<StoredBlock>`
 * (text, tool_use, tool_result, thinking, and image *references*). Screenshots
 * are never stored inline as base64; image blocks reference a [MediaEntity] row
 * whose file lives in app-private storage.
 *
 * [seq] is a 0-based per-session ordering; [role] is one of [MessageRole] and
 * [kind] one of [MessageKind].
 */
@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId"), Index(value = ["sessionId", "seq"])],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val role: String,
    val seq: Int,
    val createdAt: Long,
    val contentJson: String,
    val kind: String,
)
