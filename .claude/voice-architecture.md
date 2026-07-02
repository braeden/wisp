# Voice Architecture — STT / TTS / Wake Word (phases 08 + 09)

Shared design reference for the voice stack. Phases 08 (voice I/O) and 09 (wake
word) implement against the seams defined here. Goal: **ship on the built-in
Android APIs first, but abstract them so the backend is swappable** — cloud STT/
TTS, OpenAI/Gemini realtime voice, or on-device libraries — without touching the
agent loop.

Sources: Android `android.speech.SpeechRecognizer` and
`android.speech.tts.TextToSpeech` reference pages (read 2026-07). API-level notes
below are from those pages; treat exact `ERROR_*` numeric values as
implementation detail and branch on the **named** constants only.

---

## 1. How the built-in APIs actually perform

### `SpeechRecognizer` (STT)
- **Single-shot by design.** One `startListening(intent)` → it captures until it
  auto-endpoints on silence (or times out), fires `onResults`, and is done. There
  is **no supported continuous / always-on mode**; you re-arm per utterance.
  Re-arming mid-result throws `ERROR_RECOGNIZER_BUSY`.
- **On-device path** (Pixel 7 Pro qualifies): `createOnDeviceSpeechRecognizer()`
  + `isOnDeviceRecognitionAvailable()` (API 31+); `checkRecognitionSupport()` /
  `triggerModelDownload()` (API 33+) to probe/fetch the model. On-device →
  low-latency, offline, free, private. Set `EXTRA_PREFER_OFFLINE=true` for the
  intent-based path.
- **Callbacks** (`RecognitionListener`, all on the **main thread**):
  `onReadyForSpeech`, `onBeginningOfSpeech`, `onRmsChanged(db)` (cheap level meter
  / crude VAD), `onPartialResults` (live captions), `onEndOfSpeech`, `onResults`
  (`RESULTS_RECOGNITION` = ranked `ArrayList<String>`, optional
  `CONFIDENCE_SCORES`), `onError(code)`.
- **Errors to handle** (branch on named constants): `ERROR_NO_MATCH`,
  `ERROR_SPEECH_TIMEOUT`, `ERROR_NO_SPEECH`/no-input, `ERROR_RECOGNIZER_BUSY`,
  `ERROR_INSUFFICIENT_PERMISSIONS`, `ERROR_NETWORK*`, `ERROR_LANGUAGE_NOT_SUPPORTED`,
  `ERROR_CANNOT_CHECK_SUPPORT`/`ERROR_ONLY_ON_DEVICE_AVAILABLE` (API 31+).
- **Permission:** `RECORD_AUDIO`. Triggers the system mic-usage indicator while
  active (privacy dot).
- **Verdict for us:** great for the **`ask()` turn** (single utterance → text).
  **Not** viable for always-on wake-word (see §Wake). Endpointing can be
  aggressive; tune `EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS`. Quality is
  good for commands, weaker for long free-form dictation vs. cloud ASR.

### `TextToSpeech` (TTS)
- **Lifecycle:** construct with `OnInitListener`; wait for `SUCCESS` before use.
  `setLanguage`/`setVoice`; `getVoices()` returns `Voice` with
  `isNetworkConnectionRequired()`, `getQuality()`, `getLatency()`. `shutdown()`
  when done (not reusable after).
- **Speak + barge-in:** `speak(text, QUEUE_FLUSH|QUEUE_ADD, params, utteranceId)`.
  `QUEUE_FLUSH` preempts the current utterance (barge-in). `stop()` halts
  immediately and fires `onStop(utteranceId, interrupted=true)`. `isSpeaking()`.
- **Progress** (`UtteranceProgressListener`): `onStart`, `onDone`,
  `onStop(id, interrupted)` (API 23+), `onRangeStart(id, start, end, frame)`
  (API 26+, **word-sync highlighting** for the overlay), `onAudioAvailable`
  (API 26+, raw PCM tap if we want our own mixing/VAD-while-speaking),
  `onError(id, code)`.
- **Audio:** `setAudioAttributes(USAGE_ASSISTANT)`; request audio focus
  (transient, with ducking) around playback; also for `synthesizeToFile`.
- **Voices:** embedded (Google TTS) → low-latency, offline, free, servicable but
  synthetic. Network voices → higher quality but need connectivity and add
  latency. `setEngineByPackageName` to pick an engine.
- **Verdict for us:** fully sufficient for v1 output, and `stop()`/`QUEUE_FLUSH`
  give clean barge-in. `onRangeStart` drives caption highlighting.

### Wake word — the honest constraint
- **`SpeechRecognizer` cannot be an always-on wake detector.** Poll-looping it
  (`onResults`→`startListening`) burns battery, keeps the mic indicator lit,
  fights every other mic user, and is unreliable at keyword spotting. Acceptable
  only as a throwaway first pass.
- **OS always-on hotword** (`VoiceInteractionService` / `AlwaysOnHotwordDetector`,
  low-power DSP path) is **reserved for the device's default *assistant* role**
  app and is Google-hotword-specific — a sideloaded app can't use it in general.
- **Therefore wake word = a dedicated small model on our own `AudioRecord`
  stream**, in a foreground service:
  - **Picovoice Porcupine** — on-device, tiny, reliable, custom keywords; check
    licensing for personal sideload. *Recommended default.*
  - **openWakeWord** / **Vosk** / **sherpa-onnx KWS** — open-source, on-device;
    more integration work, no license fee.
  This is exactly why the seam below has a **separate `WakeWordDetector`** rather
  than folding wake into `SttEngine`.

---

## 2. The swappable seam (`com.assist.voice`)

Mirror the `LlmClient` pattern: **provider-agnostic interfaces in `com.assist.voice`;
each backend isolated in its own subpackage** (`voice/android`, `voice/cloud`,
`voice/realtime`, `voice/wake`). No backend SDK types leak past the interface.
Interfaces are illustrative shapes for the phase-08/09 implementer, not frozen.

```kotlin
package com.assist.voice

// ── STT ───────────────────────────────────────────────────────────────
interface SttEngine {
    suspend fun isAvailable(): Boolean
    /** One utterance: open mic → final transcript. Used by ask(). */
    suspend fun transcribeOnce(config: SttConfig = SttConfig()): SttResult
    /** Streaming: partials + endpoint events + a terminal Final. Live captions/VAD. */
    fun stream(config: SttConfig = SttConfig()): Flow<SttEvent>
    fun cancel()
}
sealed interface SttEvent {
    data object BeginningOfSpeech : SttEvent
    data class  Rms(val db: Float) : SttEvent
    data class  Partial(val text: String, val confidence: Float?) : SttEvent
    data object EndOfSpeech : SttEvent
    data class  Final(val result: SttResult) : SttEvent
    data class  Failed(val error: SttError, val cause: Throwable? = null) : SttEvent
}
data class SttResult(val text: String, val alternatives: List<String> = emptyList(), val confidence: Float? = null)
data class SttConfig(
    val locale: String = "en-US",
    val preferOnDevice: Boolean = true,
    val partialResults: Boolean = true,
    val silenceTimeoutMs: Long? = null,   // maps to EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS
    val maxAlternatives: Int = 1,
)
enum class SttError { NO_SPEECH, NO_MATCH, PERMISSION, NETWORK, BUSY,
                      LANGUAGE_UNAVAILABLE, ON_DEVICE_UNAVAILABLE, CLIENT, UNKNOWN }

// ── TTS ───────────────────────────────────────────────────────────────
interface TtsEngine {
    suspend fun isAvailable(): Boolean
    fun voices(): List<VoiceInfo>
    /** Suspends until playback completes; coroutine cancellation == stop() (barge-in). */
    suspend fun say(text: String, opts: SpeakOptions = SpeakOptions())
    fun stop()
    val isSpeaking: Boolean
    fun events(): Flow<TtsEvent>
}
sealed interface TtsEvent {
    data class Started(val utteranceId: String) : TtsEvent
    data class Range(val utteranceId: String, val start: Int, val end: Int) : TtsEvent // onRangeStart → caption sync
    data class Done(val utteranceId: String) : TtsEvent
    data class Stopped(val utteranceId: String, val interrupted: Boolean) : TtsEvent
    data class Failed(val utteranceId: String, val cause: Throwable?) : TtsEvent
}
data class SpeakOptions(
    val voiceId: String? = null, val rate: Float = 1f, val pitch: Float = 1f,
    val flush: Boolean = true, val preferOnDevice: Boolean = true,
)
data class VoiceInfo(val id: String, val locale: String, val networkRequired: Boolean,
                     val quality: Quality, val latency: Latency)
enum class Quality { LOW, NORMAL, HIGH, VERY_HIGH }
enum class Latency { VERY_LOW, LOW, NORMAL, HIGH }

// ── Wake word (own audio tap; NOT part of SttEngine) ──────────────────
interface WakeWordDetector {
    suspend fun isAvailable(): Boolean
    /** Cold, always-listening; emits once per spotted keyword until cancelled. */
    fun detections(config: WakeConfig): Flow<WakeEvent>
}
data class WakeConfig(val keyword: String, val sensitivity: Float = 0.5f, val modelAsset: String? = null)
data class WakeEvent(val keyword: String, val confidence: Float, val timestampMs: Long)

// ── Mic arbitration (one owner at a time) ─────────────────────────────
interface AudioSessionArbiter {
    /** Exactly one owner holds the mic; higher priority preempts. */
    suspend fun <T> withMic(owner: MicOwner, block: suspend () -> T): T
}
enum class MicOwner { WAKE_WORD, LISTEN_ONCE, BARGE_IN }  // priority: BARGE_IN > LISTEN_ONCE > WAKE_WORD

// ── Provider bundle + runtime selection ───────────────────────────────
interface VoiceProvider {
    val id: String                       // "android", "cloud", "openai-realtime"
    val kind: VoiceProviderKind
    fun stt(): SttEngine
    fun tts(): TtsEngine
    fun wakeWord(): WakeWordDetector?     // null → fall back to a standalone detector
}
enum class VoiceProviderKind { PIPELINE, REALTIME_DUPLEX }
```

### Backends (all behind the seam)
| Provider (`id`) | Kind | STT | TTS | Wake | Notes |
|---|---|---|---|---|---|
| `android` (**v1**) | PIPELINE | `SpeechRecognizer` (on-device) | `TextToSpeech` | — | free, offline, ships first |
| `wake-porcupine` | — | — | — | Porcupine | dedicated detector, any provider |
| `cloud` | PIPELINE | Whisper / Deepgram / Google STT | ElevenLabs / OpenAI / Azure TTS | — | quality↑, latency + $ + network |
| `openai-realtime` | REALTIME_DUPLEX | — bundled — | — bundled — | server VAD | see §3 |

`SttEngine`/`TtsEngine`/`WakeWordDetector` are independently swappable — e.g.
Android STT + ElevenLabs TTS + Porcupine wake is a valid mix. DI (a
`VoiceModule`) binds the selected `VoiceProvider` from settings; default
`android`.

---

## 3. Realtime full-duplex providers (OpenAI Realtime, Gemini Live)

These collapse STT + dialogue + TTS into **one bidirectional audio stream** with
native barge-in and the lowest conversational latency — a **different shape** from
the STT→loop→TTS pipeline, so it does **not** fit `SttEngine`+`TtsEngine` cleanly.

Decision for the seam: mark such providers `REALTIME_DUPLEX`. `VoiceUserIo`
(phase-08) is composed from `SttEngine`+`TtsEngine` for **PIPELINE** providers.
A realtime backend instead implements phase-06's `UserIo` (and a future
`RealtimeVoiceSession`) directly, owning the audio loop. **Key architectural
tension:** our agent loop is *Claude driving the phone via tools* — a realtime
voice model would still have to surface tool calls into our `AgentLoop` (or act
purely as the voice front-end that hands transcripts to Claude). v1 ships
`PIPELINE`/`android`; the `kind` flag and the `UserIo` seam keep
`REALTIME_DUPLEX` open without rework. Defer the realtime bridge until after
08/09 land.

---

## 4. Impact on the phase specs

- **Phase 08** implements the `android` provider: `SpeechRecognizer`-backed
  `SttEngine`, `TextToSpeech`-backed `TtsEngine`, `AudioSessionArbiter`, and the
  `VoiceUserIo : UserIo` composition — all behind these interfaces. Barge-in =
  `AudioSessionArbiter` giving `BARGE_IN` priority + `TtsEngine.stop()` +
  `AgentLoop.interrupt()`. Overlay captions use `SttEvent.Partial` +
  `TtsEvent.Range`.
- **Phase 09** implements `WakeWordDetector` (Porcupine default; openWakeWord
  fallback) in a foreground service, coordinated through the **same**
  `AudioSessionArbiter` so wake / `listenOnce` / barge-in never contend for the
  mic. Behind a settings toggle with a persistent listening indicator.
- **Human checkpoints:** real device for STT/TTS/wake (emulator mic is
  unreliable); Porcupine access key + licensing check for personal sideload.
