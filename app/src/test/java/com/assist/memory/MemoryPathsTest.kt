package com.assist.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryPathsTest {

    @Test
    fun `normalizes valid paths`() {
        assertEquals("/memories", MemoryPaths.normalize("/memories"))
        assertEquals("/memories/tasks/a.md", MemoryPaths.normalize("/memories/tasks/a.md"))
        assertEquals("/memories/a.txt", MemoryPaths.normalize("/memories/./a.txt"))
    }

    @Test
    fun `rejects traversal and out-of-root paths`() {
        assertNull(MemoryPaths.normalize("/memories/../secrets"))
        assertNull(MemoryPaths.normalize("/memories/%2e%2e/secrets"))
        assertNull(MemoryPaths.normalize("/memories/..\\secrets"))
        assertNull(MemoryPaths.normalize("/etc/passwd"))
        assertNull(MemoryPaths.normalize("memories/a.txt")) // not absolute
        assertNull(MemoryPaths.normalize(""))
    }

    @Test
    fun `identifies task recipes and slugs`() {
        assertTrue(MemoryPaths.isTaskRecipe("/memories/tasks/youtube-2x.md"))
        assertFalse(MemoryPaths.isTaskRecipe("/memories/tasks/nested/x.md"))
        assertFalse(MemoryPaths.isTaskRecipe("/memories/notes.md"))
        assertFalse(MemoryPaths.isTaskRecipe("/memories/tasks/x.txt"))
        assertEquals("youtube-2x", MemoryPaths.taskSlug("/memories/tasks/youtube-2x.md"))
        assertNull(MemoryPaths.taskSlug("/memories/notes.md"))
    }

    @Test
    fun `relative maps under the root`() {
        assertEquals("", MemoryPaths.relative("/memories"))
        assertEquals("tasks/a.md", MemoryPaths.relative("/memories/tasks/a.md"))
        assertNull(MemoryPaths.relative("/memories/../x"))
    }
}
