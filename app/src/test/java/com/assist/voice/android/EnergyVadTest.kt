package com.assist.voice.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnergyVadTest {

    private fun frame(amplitude: Short) = ShortArray(160) { amplitude }

    @Test
    fun `rms of silence is zero`() {
        val vad = EnergyVad()
        assertEquals(0.0, vad.rms(ShortArray(160)), 0.001)
    }

    @Test
    fun `constant amplitude rms equals amplitude`() {
        val vad = EnergyVad()
        assertEquals(2000.0, vad.rms(frame(2000)), 0.5)
    }

    @Test
    fun `sustained loud frames trigger after threshold count`() {
        val vad = EnergyVad(thresholdRms = 1500.0, triggerFrames = 3)
        assertFalse(vad.onFrame(frame(3000)))
        assertFalse(vad.onFrame(frame(3000)))
        assertTrue(vad.onFrame(frame(3000)))
    }

    @Test
    fun `quiet frames never trigger`() {
        val vad = EnergyVad(thresholdRms = 1500.0, triggerFrames = 3)
        repeat(10) { assertFalse(vad.onFrame(frame(200))) }
    }

    @Test
    fun `a quiet frame resets the consecutive run`() {
        val vad = EnergyVad(thresholdRms = 1500.0, triggerFrames = 3)
        assertFalse(vad.onFrame(frame(3000)))
        assertFalse(vad.onFrame(frame(3000)))
        assertFalse(vad.onFrame(frame(100))) // resets
        assertFalse(vad.onFrame(frame(3000)))
        assertFalse(vad.onFrame(frame(3000)))
        assertTrue(vad.onFrame(frame(3000)))
    }

    @Test
    fun `counter resets after a positive detection so it can re-arm`() {
        val vad = EnergyVad(thresholdRms = 1500.0, triggerFrames = 2)
        assertFalse(vad.onFrame(frame(3000)))
        assertTrue(vad.onFrame(frame(3000)))
        // Re-armed: needs a fresh run.
        assertFalse(vad.onFrame(frame(3000)))
        assertTrue(vad.onFrame(frame(3000)))
    }
}
