package com.assist.voice

import kotlinx.coroutines.flow.Flow

/**
 * Always-listening keyword spotter on its own audio tap — deliberately **not**
 * part of [SttEngine] (see `.claude/voice-architecture.md`: `SpeechRecognizer`
 * can't be an always-on wake detector). Implemented in phase-09 (Porcupine /
 * openWakeWord) and coordinated with `listenOnce`/barge-in through the shared
 * [AudioSessionArbiter]. Declared here so [VoiceProvider] can reference it; the
 * phase-08 `android` provider returns `null`.
 */
interface WakeWordDetector {
    suspend fun isAvailable(): Boolean

    /** Cold, always-listening; emits once per spotted keyword until cancelled. */
    fun detections(config: WakeConfig): Flow<WakeEvent>
}

data class WakeConfig(
    val keyword: String,
    val sensitivity: Float = 0.5f,
    val modelAsset: String? = null,
)

data class WakeEvent(
    val keyword: String,
    val confidence: Float,
    val timestampMs: Long,
)
