package com.assist.ui.sessions

import com.assist.data.TaskRecipe
import org.junit.Assert.assertEquals
import org.junit.Test

class RecipesReducerTest {
    @Test
    fun `maps recipes to rows`() {
        val state =
            RecipesReducer.reduce(
                listOf(
                    TaskRecipe(
                        id = 1,
                        title = "YouTube 2x",
                        appPackage = "com.google.android.youtube",
                        useCount = 3,
                        lastUsedAt = 100,
                        createdAt = 1,
                        memoryPath = "/memories/tasks/youtube-2x.md",
                        intentKeywords = "youtube speed",
                    ),
                ),
            )
        assertEquals(false, state.loading)
        val row = state.rows.single()
        assertEquals("YouTube 2x", row.title)
        assertEquals("com.google.android.youtube", row.appPackage)
        assertEquals(3, row.useCount)
        assertEquals("/memories/tasks/youtube-2x.md", row.memoryPath)
    }

    @Test
    fun `blank title falls back`() {
        val state =
            RecipesReducer.reduce(
                listOf(TaskRecipe(1, "", null, 0, 0, 0, "/memories/tasks/x.md", "")),
            )
        assertEquals("Untitled recipe", state.rows.single().title)
    }
}
