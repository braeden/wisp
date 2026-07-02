package com.assist.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface ToolCallDao {

    @Insert
    suspend fun insert(toolCall: ToolCallEntity): Long

    @Query("SELECT * FROM tool_calls WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    suspend fun getForSession(sessionId: Long): List<ToolCallEntity>
}
