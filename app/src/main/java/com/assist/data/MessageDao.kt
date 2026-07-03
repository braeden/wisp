package com.assist.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Insert
    suspend fun insert(message: MessageEntity): Long

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY seq ASC")
    suspend fun getForSession(sessionId: Long): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY seq ASC")
    fun observeForSession(sessionId: Long): Flow<List<MessageEntity>>

    @Query("SELECT MAX(seq) FROM messages WHERE sessionId = :sessionId")
    suspend fun maxSeq(sessionId: Long): Int?

    @Query("DELETE FROM messages WHERE sessionId = :sessionId AND seq < :beforeSeq")
    suspend fun deleteBeforeSeq(
        sessionId: Long,
        beforeSeq: Int,
    )

    @Query("DELETE FROM messages WHERE sessionId = :sessionId")
    suspend fun deleteAll(sessionId: Long)
}
