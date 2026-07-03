package com.assist.voice

/**
 * A bundled voice backend — STT + TTS (+ optional wake word). Mirrors the
 * `LlmClient` provider pattern: DI binds the selected provider (default
 * `android`). Realtime full-duplex backends (OpenAI/Gemini) advertise
 * [VoiceProviderKind.REALTIME_DUPLEX] and implement `UserIo` directly instead of
 * composing from STT+TTS — see `.claude/voice-architecture.md` §3.
 */
interface VoiceProvider {
    val id: String
    val kind: VoiceProviderKind

    fun stt(): SttEngine

    fun tts(): TtsEngine

    /** `null` → no bundled wake word; fall back to a standalone detector (phase-09). */
    fun wakeWord(): WakeWordDetector?
}

enum class VoiceProviderKind { PIPELINE, REALTIME_DUPLEX }
