package com.assist.voice.android

import com.assist.voice.SttEngine
import com.assist.voice.TtsEngine
import com.assist.voice.VoiceProvider
import com.assist.voice.VoiceProviderKind
import com.assist.voice.WakeWordDetector

/**
 * The v1 `android` [VoiceProvider]: `SpeechRecognizer` STT + `TextToSpeech` TTS,
 * a `PIPELINE` backend. Free, offline, ships first. Wake word is deferred to
 * phase-09 (a standalone [WakeWordDetector]), so [wakeWord] returns `null`.
 */
class AndroidVoiceProvider(
    private val stt: SttEngine,
    private val tts: TtsEngine,
) : VoiceProvider {
    override val id: String = "android"
    override val kind: VoiceProviderKind = VoiceProviderKind.PIPELINE
    override fun stt(): SttEngine = stt
    override fun tts(): TtsEngine = tts
    override fun wakeWord(): WakeWordDetector? = null
}
