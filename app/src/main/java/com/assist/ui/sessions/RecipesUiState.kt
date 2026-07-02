package com.assist.ui.sessions

import com.assist.data.TaskRecipe

/** One row in the learned-tasks (recipes) browser. */
data class RecipeRowUi(
    val id: Long,
    val title: String,
    val appPackage: String?,
    val useCount: Int,
    val lastUsedAt: Long,
    val memoryPath: String,
)

data class RecipesUiState(
    val rows: List<RecipeRowUi> = emptyList(),
    val loading: Boolean = true,
)

/** Pure reducer (phase-12): maps [TaskRecipe]s to recipe-browser rows. */
object RecipesReducer {
    fun reduce(recipes: List<TaskRecipe>): RecipesUiState =
        RecipesUiState(
            loading = false,
            rows = recipes.map {
                RecipeRowUi(
                    id = it.id,
                    title = it.title.ifBlank { "Untitled recipe" },
                    appPackage = it.appPackage,
                    useCount = it.useCount,
                    lastUsedAt = it.lastUsedAt,
                    memoryPath = it.memoryPath,
                )
            },
        )
}
