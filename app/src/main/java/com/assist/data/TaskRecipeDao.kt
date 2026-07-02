package com.assist.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskRecipeDao {

    @Insert
    suspend fun insert(recipe: TaskRecipeEntity): Long

    @Update
    suspend fun update(recipe: TaskRecipeEntity)

    @Query("SELECT * FROM task_recipes ORDER BY lastUsedAt DESC")
    fun observeAll(): Flow<List<TaskRecipeEntity>>

    @Query("SELECT * FROM task_recipes ORDER BY lastUsedAt DESC")
    suspend fun getAll(): List<TaskRecipeEntity>

    @Query("SELECT * FROM task_recipes WHERE id = :id")
    suspend fun getById(id: Long): TaskRecipeEntity?

    @Query("SELECT * FROM task_recipes WHERE memoryPath = :path")
    suspend fun getByPath(path: String): TaskRecipeEntity?

    @Query("DELETE FROM task_recipes WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM task_recipes WHERE memoryPath = :path")
    suspend fun deleteByPath(path: String)

    @Query("UPDATE task_recipes SET useCount = useCount + 1, lastUsedAt = :now WHERE memoryPath = :path")
    suspend fun recordUse(path: String, now: Long)
}
