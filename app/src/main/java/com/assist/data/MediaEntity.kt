package com.assist.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A media file (currently only screenshots) associated with a session. The bytes
 * live on disk under app-private storage; this row holds only the [path] and
 * metadata. [dropped] marks the file as evicted from context (context-shrink);
 * the file may still exist on disk but is omitted / placeholdered on rebuild.
 */
@Entity(
    tableName = "media",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId"), Index(value = ["sessionId", "kind"])],
)
data class MediaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val path: String,
    /** One of [MediaKind]. */
    val kind: String,
    val createdAt: Long,
    val dropped: Boolean = false,
)
