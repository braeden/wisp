package com.assist.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GesturesTest {
    private val screen = Bounds(0, 0, 1000, 2000) // cx=500, cy=1000

    @Test
    fun `swipe up moves finger from lower to upper, same x`() {
        val s = Gestures.swipe(SwipeDirection.UP, screen, fraction = 0.6)
        assertEquals(500, s.start.x)
        assertEquals(500, s.end.x)
        assertTrue("start below end", s.start.y > s.end.y)
        // half = height*0.6/2 = 600
        assertEquals(1600, s.start.y)
        assertEquals(400, s.end.y)
    }

    @Test
    fun `swipe down is the vertical mirror of up`() {
        val s = Gestures.swipe(SwipeDirection.DOWN, screen, fraction = 0.6)
        assertEquals(400, s.start.y)
        assertEquals(1600, s.end.y)
    }

    @Test
    fun `swipe left moves finger right-to-left, same y`() {
        val s = Gestures.swipe(SwipeDirection.LEFT, screen, fraction = 0.6)
        assertEquals(1000, s.start.y)
        assertEquals(1000, s.end.y)
        assertTrue(s.start.x > s.end.x)
    }

    @Test
    fun `fraction is clamped to a sane range`() {
        val huge = Gestures.swipe(SwipeDirection.UP, screen, fraction = 5.0)
        // clamped to 0.95 => half = 950
        assertEquals(1000 + 950, huge.start.y)
    }

    @Test
    fun `center of bounds`() {
        val p = Gestures.center(Bounds(10, 20, 110, 220))
        assertEquals(60, p.x)
        assertEquals(120, p.y)
    }
}
