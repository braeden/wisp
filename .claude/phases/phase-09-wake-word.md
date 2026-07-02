# Phase 09 — Wake Word (exploratory)

**Objective:** hands-free activation via a wake word (e.g. "Hey Assist"), like
Siri/Google/Alexa. Explicitly optional / later — build behind a setting.

**Prerequisites:** phase-08 (voice pipeline, `Listener`, audio focus).
**Parallelizable with 10/11.** Owns wake-word code in `com.assist.voice`.

**Design reference:** [`../voice-architecture.md`](../voice-architecture.md) —
implement the **`WakeWordDetector`** seam (own `AudioRecord` tap, NOT
`SpeechRecognizer`, which is single-shot and unfit for always-on) and route all
mic access through the shared `AudioSessionArbiter`. Note: OS always-on hotword
(`AlwaysOnHotwordDetector`/`VoiceInteractionService`) is reserved for the default
assistant role — unavailable to a sideloaded app — which is why a dedicated
on-device detector (Porcupine default) is required.

## Options (pick one; document the choice + licensing)
- **Picovoice Porcupine** — on-device, small, reliable, custom keywords; check
  licensing for personal/sideload use. Recommended default.
- **openWakeWord / Vosk** — open-source, on-device; more integration work.
- **`SpeechRecognizer` keyword spotting** — simplest but battery-heavy and less
  reliable; acceptable for a first pass.

## Deliverables
1. **`WakeWordService`** — a foreground service (or integrated into the existing
   FGS) running the detector on a mic stream; on detection: acquire audio focus,
   stop any TTS, and start `listenOnce()` → dispatch the intent to `AgentLoop`.
2. **Settings toggle** + battery/latency notes; respects a global "listening"
   privacy indicator and easy disable.
3. **Model asset** (keyword) bundled or downloaded; documented setup.
4. Coexistence with barge-in VAD (phase-08) — one owner of the mic at a time;
   define an `AudioSessionArbiter` so wake-word, barge-in, and `listenOnce`
   don't fight over the microphone.

## Contracts I consume
- `com.assist.voice.Listener`, `Speaker`, `AudioSessionArbiter` (new, owned here
  or shared with 08), `AgentLoop`.

## Acceptance criteria
- Saying the wake word while the app is backgrounded starts a listening session
  and a spoken intent runs a task.
- Toggle off fully stops mic capture; a persistent indicator shows when active.
- No mic contention crashes when combined with barge-in.

## Verification
```bash
bash scripts/install.sh   # enable wake word in settings
# background the app; say the wake word; then an intent
bash scripts/logcat.sh
```

## Notes
- Battery: continuous mic is costly; document expected drain and offer a
  screen-on-only mode. This phase is a research spike — a working prototype and a
  recommendation are acceptable deliverables even if not production-hardened.
