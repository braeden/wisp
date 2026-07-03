package com.assist.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    @Insert
    suspend fun insert(note: NoteEntity): Long

    @Query("SELECT * FROM notes WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    suspend fun getForSession(sessionId: Long): List<NoteEntity>

    @Query("SELECT * FROM notes WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    fun observeForSession(sessionId: Long): Flow<List<NoteEntity>>
}
