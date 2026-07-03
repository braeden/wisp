package com.assist.voice

import kotlinx.coroutines.flow.Flow

/**
 * Text-to-speech seam. The Android `TextToSpeech` backend lives in
 * `com.assist.voice.android`; a cloud TTS (ElevenLabs/Azure) can swap in behind
 * this interface. See `.claude/voice-architecture.md`.
 */
interface TtsEngine {
    /** Whether the engine finished initializing and can speak. */
    suspend fun isAvailable(): Boolean

    fun voices(): List<VoiceInfo>

    /**
     * Speak [text], suspending until playback completes. Cancelling the coroutine
     * stops playback immediately (barge-in) — the same effect as [stop].
     */
    suspend fun say(
        text: String,
        opts: SpeakOptions = SpeakOptions(),
    )

    /** Halt current + queued speech immediately (barge-in / `QUEUE_FLUSH`+stop). */
    fun stop()

    val isSpeaking: Boolean

    /** Utterance lifecycle events (start / word-range / done / stopped / failed). */
    fun events(): Flow<TtsEvent>
}

sealed interface TtsEvent {
    data class Started(
        val utteranceId: String,
    ) : TtsEvent

    /** `onRangeStart` → drives caption highlighting in the overlay (phase-07). */
    data class Range(
        val utteranceId: String,
        val start: Int,
        val end: Int,
    ) : TtsEvent

    data class Done(
        val utteranceId: String,
    ) : TtsEvent

    data class Stopped(
        val utteranceId: String,
        val interrupted: Boolean,
    ) : TtsEvent

    data class Failed(
        val utteranceId: String,
        val cause: Throwable? = null,
    ) : TtsEvent
}

data class SpeakOptions(
    val voiceId: String? = null,
    val rate: Float = 1f,
    val pitch: Float = 1f,
    val flush: Boolean = true,
    val preferOnDevice: Boolean = true,
)

data class VoiceInfo(
    val id: String,
    val locale: String,
    val networkRequired: Boolean,
    val quality: Quality,
    val latency: Latency,
)

enum class Quality { LOW, NORMAL, HIGH, VERY_HIGH }

enum class Latency { VERY_LOW, LOW, NORMAL, HIGH }
