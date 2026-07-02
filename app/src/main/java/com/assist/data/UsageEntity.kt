package com.assist.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Token usage and computed cost for one model turn. [costUsd] is priced at
 * record time by [CostCalculator] from the [model] id and token counts, so
 * historical rows keep the price that applied when they were written.
 */
@Entity(
    tableName = "usage",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId"), Index("createdAt")],
)
data class UsageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val messageId: Long?,
    val model: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val cacheReadTokens: Int,
    val cacheWriteTokens: Int,
    val costUsd: Double,
    val createdAt: Long,
)
