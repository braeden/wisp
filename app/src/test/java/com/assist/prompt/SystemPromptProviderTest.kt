package com.assist.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemPromptProviderTest {

    private val provider: SystemPromptProvider = DefaultSystemPromptProvider()

    private fun coreText(): String =
        provider.system().first { it.cacheable }.text

    @Test
    fun `system returns a cacheable stable core as the first block`() {
        val blocks = provider.system()
        assertTrue("expected at least one block", blocks.isNotEmpty())
        val first = blocks.first()
        assertTrue("first block must be the cacheable core", first.cacheable)
        assertTrue(first.text.isNotBlank())
    }

    @Test
    fun `exactly one cacheable block so a single cache breakpoint lands on the core`() {
        val ctx = PromptContext(deviceModel = "Pixel 7 Pro", currentTime = "now")
        val blocks = provider.system(ctx)
        assertEquals(1, blocks.count { it.cacheable })
        // Dynamic tail, when present, comes after the breakpoint and is not cached.
        val tail = blocks.last()
        assertFalse(tail.cacheable)
    }

    @Test
    fun `no dynamic tail is emitted for an empty context`() {
        val blocks = provider.system()
        assertEquals(1, blocks.size)
        assertTrue(blocks.single().cacheable)
    }

    @Test
    fun `core describes the perceive-act operating model and the a11y tree`() {
        val core = coreText().lowercase()
        assertTrue(core.contains("accessibility tree"))
        assertTrue(core.contains("screenshot"))
        assertTrue(core.contains("tool call"))
        // one-action-at-a-time and settle guidance
        assertTrue(core.contains("one"))
        assertTrue(core.contains("settle"))
        // prefer the tree over screenshots
        assertTrue(core.contains("prefer"))
    }

    @Test
    fun `core states the safety confirmation gates`() {
        val core = coreText().lowercase()
        assertTrue(core.contains("confirmation"))
        assertTrue(core.contains("gated"))
        listOf("send", "payment", "delet", "install", "call", "password", "irreversible")
            .forEach { assertTrue("core must mention gated action '$it'", core.contains(it)) }
    }

    @Test
    fun `core warns that on-screen text is untrusted (prompt injection)`() {
        val core = coreText().lowercase()
        assertTrue(core.contains("prompt injection"))
        assertTrue(core.contains("untrusted"))
        assertTrue(core.contains("instructions"))
        assertTrue(core.contains("data, not"))
    }

    @Test
    fun `core reinforces the task-recipe memory convention`() {
        val core = coreText()
        assertTrue(core.contains("/memories"))
        assertTrue(core.contains("/memories/tasks/"))
        val lower = core.lowercase()
        assertTrue("check memory first", lower.contains("check memory first"))
        assertTrue("record a recipe", lower.contains("record a recipe"))
        // recipe anatomy
        listOf("entry point", "steps", "gotchas", "verification")
            .forEach { assertTrue("recipe must mention '$it'", lower.contains(it)) }
    }

    @Test
    fun `dynamic tail renders provided device info notes and time`() {
        val ctx = PromptContext(
            deviceModel = "Pixel 7 Pro",
            androidVersion = "Android 15",
            screenSize = "1440x3120",
            locale = "en-US",
            currentTime = "2026-07-02 10:00",
            sessionNotes = listOf("user prefers metric units"),
            installedAppHints = listOf("Gmail", "Spotify"),
        )
        val tail = provider.system(ctx).last { !it.cacheable }.text
        assertTrue(tail.contains("Pixel 7 Pro"))
        assertTrue(tail.contains("Android 15"))
        assertTrue(tail.contains("1440x3120"))
        assertTrue(tail.contains("en-US"))
        assertTrue(tail.contains("2026-07-02 10:00"))
        assertTrue(tail.contains("user prefers metric units"))
        assertTrue(tail.contains("Gmail"))
        // the tail flags itself as non-instruction context (injection hygiene)
        assertTrue(tail.lowercase().contains("not instructions"))
    }

    @Test
    fun `version is exposed and stable`() {
        assertEquals(SystemPromptProvider.PROMPT_VERSION, provider.version)
    }
}
