package com.wisp.voice.wake

import ai.picovoice.porcupine.Porcupine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PorcupineKeywordsTest {
    @Test
    fun `resolves enum names case-insensitively`() {
        assertEquals(Porcupine.BuiltInKeyword.PORCUPINE, PorcupineKeywords.builtInFor("porcupine"))
        assertEquals(Porcupine.BuiltInKeyword.JARVIS, PorcupineKeywords.builtInFor("  Jarvis "))
    }

    @Test
    fun `resolves spaced and hyphenated names`() {
        assertEquals(Porcupine.BuiltInKeyword.HEY_GOOGLE, PorcupineKeywords.builtInFor("hey google"))
        assertEquals(Porcupine.BuiltInKeyword.HEY_SIRI, PorcupineKeywords.builtInFor("hey-siri"))
    }

    @Test
    fun `unknown keyword resolves to null`() {
        assertNull(PorcupineKeywords.builtInFor("hey wisp"))
        assertNull(PorcupineKeywords.builtInFor(""))
    }

    @Test
    fun `allNames round-trips through builtInFor`() {
        val names = PorcupineKeywords.allNames()
        assertTrue(names.isNotEmpty())
        names.forEach { name ->
            assertEquals(name, PorcupineKeywords.builtInFor(name)?.name)
        }
    }

    @Test
    fun `display names are human readable`() {
        assertEquals("Hey google", PorcupineKeywords.displayName("HEY_GOOGLE"))
        assertEquals("Porcupine", PorcupineKeywords.displayName("PORCUPINE"))
    }
}
