package com.assist.voice

import kotlinx.coroutines.flow.Flow

/**
 * Speech-to-text seam. Provider-agnostic: the Android `SpeechRecognizer` backend
 * lives in `com.assist.voice.android`; a cloud/Whisper backend can swap in without
 * touching callers. See `.claude/voice-architecture.md`.
 */
interface SttEngine {
    /** Whether recognition is usable right now (permission-independent capability). */
    suspend fun isAvailable(): Boolean

    /**
     * One utterance: open the mic, capture until endpoint, return the final
     * transcript. Used by `ask()`. Throws [SttException] on failure. Cancelling
     * the coroutine aborts the capture.
     */
    suspend fun transcribeOnce(config: SttConfig = SttConfig()): SttResult

    /**
     * Streaming recognition: partials + rms + endpoint events + a terminal
     * [SttEvent.Final] (or [SttEvent.Failed]). Drives live captions and VAD.
     * The flow completes after the terminal event; cancel the collector to stop.
     */
    fun stream(config: SttConfig = SttConfig()): Flow<SttEvent>

    /** Abort any in-flight recognition and release the recognizer. */
    fun cancel()
}

/** Streaming recognition events (mirrors the `RecognitionListener` callbacks). */
sealed interface SttEvent {
    data object BeginningOfSpeech : SttEvent

    data class Rms(
        val db: Float,
    ) : SttEvent

    data class Partial(
        val text: String,
        val confidence: Float? = null,
    ) : SttEvent

    data object EndOfSpeech : SttEvent

    data class Final(
        val result: SttResult,
    ) : SttEvent

    data class Failed(
        val error: SttError,
        val cause: Throwable? = null,
    ) : SttEvent
}

data class SttResult(
    val text: String,
    val alternatives: List<String> = emptyList(),
    val confidence: Float? = null,
)

data class SttConfig(
    val locale: String = "en-US",
    val preferOnDevice: Boolean = true,
    val partialResults: Boolean = true,
    /** Maps to `EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS`. */
    val silenceTimeoutMs: Long? = null,
    val maxAlternatives: Int = 1,
)

/** Backend-neutral error taxonomy; `SpeechRecognizer.ERROR_*` maps into this. */
enum class SttError {
    NO_SPEECH,
    NO_MATCH,
    PERMISSION,
    NETWORK,
    BUSY,
    LANGUAGE_UNAVAILABLE,
    ON_DEVICE_UNAVAILABLE,
    CLIENT,
    UNKNOWN,
}

/** Thrown by [SttEngine.transcribeOnce] when recognition fails. */
class SttException(
    val error: SttError,
    cause: Throwable? = null,
) : Exception("STT failed: $error", cause)
