package com.assist.voice.android

import kotlin.math.sqrt

/**
 * Tiny energy-based voice-activity detector: computes RMS over a PCM-16 frame and
 * fires once a run of [triggerFrames] consecutive frames clears [thresholdRms].
 * Pure and stateful — unit-tested without an `AudioRecord`. This is the coarse
 * gate that wakes barge-in (per `.claude/voice-architecture.md`,
 * `SpeechRecognizer` can't listen while TTS plays, so an `AudioRecord` energy tap
 * triggers, then hands off to the recognizer).
 */
class EnergyVad(
    private val thresholdRms: Double = 1500.0,
    private val triggerFrames: Int = 3,
) {
    private var consecutive = 0

    /** RMS amplitude (0..~32767) of [frame]'s first [length] samples. */
    fun rms(frame: ShortArray, length: Int = frame.size): Double {
        if (length <= 0) return 0.0
        var sum = 0.0
        for (i in 0 until length) {
            val s = frame[i].toDouble()
            sum += s * s
        }
        return sqrt(sum / length)
    }

    /**
     * Feed one frame; returns true the moment sustained speech is detected. After
     * a positive detection the counter resets so the caller can re-arm.
     */
    fun onFrame(frame: ShortArray, length: Int = frame.size): Boolean {
        val loud = rms(frame, length) >= thresholdRms
        consecutive = if (loud) consecutive + 1 else 0
        if (consecutive >= triggerFrames) {
            consecutive = 0
            return true
        }
        return false
    }

    fun reset() {
        consecutive = 0
    }
}
