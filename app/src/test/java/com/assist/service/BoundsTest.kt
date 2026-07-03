package com.assist.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundsTest {
    @Test
    fun `center is midpoint`() {
        val b = Bounds(10, 20, 110, 220)
        assertEquals(60, b.centerX)
        assertEquals(120, b.centerY)
    }

    @Test
    fun `width and height`() {
        val b = Bounds(10, 20, 110, 80)
        assertEquals(100, b.width)
        assertEquals(60, b.height)
    }

    @Test
    fun `degenerate rect centers on its edge and is empty`() {
        val b = Bounds(50, 50, 50, 50)
        assertEquals(50, b.centerX)
        assertEquals(50, b.centerY)
        assertTrue(b.isEmpty)
    }

    @Test
    fun `negative-area rect is empty`() {
        assertTrue(Bounds(100, 100, 10, 10).isEmpty)
        assertFalse(Bounds(0, 0, 1, 1).isEmpty)
    }

    @Test
    fun `contains is half-open`() {
        val b = Bounds(0, 0, 100, 100)
        assertTrue(b.contains(0, 0))
        assertTrue(b.contains(99, 99))
        assertFalse(b.contains(100, 100))
        assertFalse(b.contains(-1, 50))
    }
}
