package com.assist.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A lightweight **index** over a learned-task recipe stored as a memory file at
 * `/memories/tasks/<slug>.md` (phase-12). This is NOT a second source of truth —
 * the recipe body lives in the memory file; this row exists so the UI can list,
 * search, and prune recipes, and so the agent can be pre-seeded a "recall hint".
 *
 * [memoryPath] is the logical memory path (unique). [intentKeywords] is a
 * space-joined bag of words derived from the title/slug for cheap recall.
 * Maintained by `TaskMemoryRepository` as the agent writes/uses recipes.
 */
@Entity(
    tableName = "task_recipes",
    indices = [Index(value = ["memoryPath"], unique = true)],
)
data class TaskRecipeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val intentKeywords: String,
    val memoryPath: String,
    val appPackage: String?,
    val useCount: Int,
    val lastUsedAt: Long,
    val createdAt: Long,
)
