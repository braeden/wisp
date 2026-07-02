package com.assist.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface UsageDao {

    @Insert
    suspend fun insert(usage: UsageEntity): Long

    @Query("SELECT * FROM usage WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    suspend fun getForSession(sessionId: Long): List<UsageEntity>

    @Query("SELECT COALESCE(SUM(costUsd), 0.0) FROM usage WHERE sessionId = :sessionId")
    suspend fun sessionCost(sessionId: Long): Double

    @Query("SELECT COALESCE(SUM(costUsd), 0.0) FROM usage WHERE createdAt >= :sinceMillis")
    suspend fun costSince(sinceMillis: Long): Double
}
