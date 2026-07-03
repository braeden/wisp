package com.assist.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface MediaDao {
    @Insert
    suspend fun insert(media: MediaEntity): Long

    @Query("SELECT * FROM media WHERE id = :id")
    suspend fun getById(id: Long): MediaEntity?

    @Query("SELECT * FROM media WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    suspend fun getForSession(sessionId: Long): List<MediaEntity>

    @Query(
        "SELECT COUNT(*) FROM media " +
            "WHERE sessionId = :sessionId AND kind = :kind AND dropped = 0",
    )
    suspend fun countLive(
        sessionId: Long,
        kind: String,
    ): Int

    /** Live (not dropped) media ids of the given kind, newest first. */
    @Query(
        "SELECT id FROM media " +
            "WHERE sessionId = :sessionId AND kind = :kind AND dropped = 0 " +
            "ORDER BY createdAt DESC",
    )
    suspend fun liveIdsNewestFirst(
        sessionId: Long,
        kind: String,
    ): List<Long>

    @Query("UPDATE media SET dropped = 1 WHERE id IN (:ids)")
    suspend fun markDropped(ids: List<Long>)
}
