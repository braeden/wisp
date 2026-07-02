package com.assist.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {

    @Insert
    suspend fun insert(session: SessionEntity): Long

    @Update
    suspend fun update(session: SessionEntity)

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun getById(id: Long): SessionEntity?

    @Query("SELECT * FROM sessions WHERE id = :id")
    fun observeById(id: Long): Flow<SessionEntity?>

    @Query("SELECT * FROM sessions ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    /** Sessions with their message count and running cost, most-recent first. */
    @Query(
        "SELECT s.*, " +
            "(SELECT COUNT(*) FROM messages m WHERE m.sessionId = s.id) AS messageCount, " +
            "(SELECT COALESCE(SUM(u.costUsd), 0.0) FROM usage u WHERE u.sessionId = s.id) AS costUsd " +
            "FROM sessions s ORDER BY s.updatedAt DESC",
    )
    fun observeSummaries(): Flow<List<SessionSummary>>

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE sessions SET title = :title, updatedAt = :now WHERE id = :id")
    suspend fun rename(id: Long, title: String, now: Long)

    @Query("UPDATE sessions SET status = :status, updatedAt = :now WHERE id = :id")
    suspend fun setStatus(id: Long, status: String, now: Long)

    @Query("UPDATE sessions SET updatedAt = :now WHERE id = :id")
    suspend fun touch(id: Long, now: Long)
}
