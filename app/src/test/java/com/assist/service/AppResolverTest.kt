package com.assist.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppResolverTest {
    private val resolver = AppResolver()
    private val apps =
        listOf(
            InstalledApp("com.android.settings", "Settings"),
            InstalledApp("com.google.android.gm", "Gmail"),
            InstalledApp("com.google.android.apps.maps", "Maps"),
            InstalledApp("com.spotify.music", "Spotify"),
        )

    @Test
    fun `exact package id wins`() {
        assertEquals("com.google.android.gm", resolver.resolve("com.google.android.gm", apps))
    }

    @Test
    fun `exact label case-insensitive`() {
        assertEquals("com.android.settings", resolver.resolve("settings", apps))
        assertEquals("com.spotify.music", resolver.resolve("Spotify", apps))
    }

    @Test
    fun `starts-with beats contains`() {
        val extra = apps + InstalledApp("com.x.setup", "Setup Wizard")
        // "set" starts Settings and Setup; shortest label wins among starts-with.
        assertEquals("com.android.settings", resolver.resolve("Set", extra))
    }

    @Test
    fun `contains match`() {
        assertEquals("com.google.android.apps.maps", resolver.resolve("ap", apps))
    }

    @Test
    fun `package substring fallback`() {
        assertEquals("com.spotify.music", resolver.resolve("spotify.music", apps))
    }

    @Test
    fun `no match returns null`() {
        assertNull(resolver.resolve("nonexistent", apps))
        assertNull(resolver.resolve("", apps))
    }
}
