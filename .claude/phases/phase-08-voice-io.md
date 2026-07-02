# Phase 08 — Voice I/O + Interruptibility

**Objective:** speak (`say`), listen for intents and answers (`ask`), and support
**barge-in** — the user can interrupt the assistant while it's talking/acting,
like Siri/Google/ChatGPT voice.

**Prerequisites:** phase-06 (`UserIo` interface, `AgentLoop.interrupt`,
`AgentEventBus`). **Parallelizable with 07.** Owns `com.assist.voice`.

**Design reference:** [`../voice-architecture.md`](../voice-architecture.md) —
the swappable STT/TTS/wake seam and the SpeechRecognizer/TextToSpeech findings.
Implement the **`android` `VoiceProvider`** (SpeechRecognizer `SttEngine` +
TextToSpeech `TtsEngine` + `AudioSessionArbiter`); keep every backend behind the
interfaces there so cloud/OpenAI-realtime/on-device engines swap in later.
The `Speaker`/`Listener` below are the `android`-provider impls of
`TtsEngine`/`SttEngine`.

## Deliverables
1. **TTS `Speaker`** — wraps `TextToSpeech`; `suspend fun say(text)` that resolves
   when playback finishes; `stop()` for barge-in; emits `Speaking` start/stop to
   the bus. Handles queueing and language/voice selection.
2. **STT `Listener`** — wraps Android `SpeechRecognizer` (on-device on Pixel):
   - `suspend fun listenOnce(): String` for `ask()` (final transcript).
   - `fun continuousPartials(): Flow<Partial>` for live captions + VAD.
   - Handles permission, no-speech timeouts, errors/retries.
3. **VAD / barge-in `BargeInDetector`** — runs lightweight voice-activity
   detection while TTS is playing; on detected speech: `Speaker.stop()` +
   `AgentLoop.interrupt()` + start `listenOnce()` to capture the redirect. This
   is the core interruptibility behavior. (On-device VAD; if `SpeechRecognizer`
   can't run concurrently with TTS reliably, use an `AudioRecord` energy/webrtc-vad
   gate to trigger, then hand off to the recognizer.)
4. **`VoiceUserIo : UserIo`** (the real implementation of phase-06's interface):
   - `say(text)` → `Speaker` (+ overlay).
   - `ask(question)` → `say(question)` then `listenOnce()`; also accept a typed
     reply from the overlay (whichever returns first).
5. **Session voice control** — voice commands to start a new session / resume /
   switch ("new task", "resume my last session"), routed to `SessionRepository`.
6. **Push-to-talk + hands-free** entry: a mic button in the overlay and a
   foreground mic mode; full wake-word is phase-09.
7. **Audio focus** management (ducking, pausing music, headset routing).

## Contracts I own / consume
- Provide `com.assist.voice.VoiceUserIo` bound to `com.assist.agent.UserIo` (Hilt).
- Consume `AgentEventBus`, `AgentLoop.interrupt()`, `SessionRepository`.

## Steps
1. `Speaker` + `Listener` with a manual test screen (say / listen buttons).
2. `VoiceUserIo`; swap out phase-06's stub via DI; run a full **voice-in** task
   end-to-end (speak intent → agent runs → spoken summary).
3. `BargeInDetector`; verify interrupting mid-speech and mid-action redirects.
4. Voice session commands + audio focus polish.

## Acceptance criteria
- Speaking an intent drives a full agent task and the assistant speaks a summary.
- While the assistant is speaking, saying something stops it within ~1s and
  captures the new instruction (barge-in).
- `ask()` questions are answered by voice **or** typed overlay reply.
- Mic permission flow works; recognizer errors don't crash the loop.

## Verification
```bash
bash scripts/install.sh
# grant mic permission; use overlay mic; speak: "open Clock and start a timer"
bash scripts/logcat.sh
```

## Human checkpoint
Real device strongly preferred (emulator mic/STT is unreliable).

## Notes
- Prefer on-device recognition (`RecognizerIntent` / `SpeechRecognizer` with
  on-device flag) for latency/privacy; document a cloud/Whisper fallback as
  optional. Keep the recognizer behind the `Listener` interface so it's swappable.
