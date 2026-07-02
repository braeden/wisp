# CLAUDE.md — Assist progress log

Running log of what has landed, for humans and models picking up the work.
Plan and per-phase specs: [`.claude/`](.claude/README.md).

## Project snapshot
- **Goal:** on-device AI phone agent (Kotlin/Android) that drives the phone via
  Accessibility APIs from Claude tool calls, by voice. Personal sideload target
  (Pixel 7 Pro). See `.claude/phases/phase-00-decisions.md`.
- **Locked decisions:** sideload-only; hybrid perception (a11y tree + on-demand
  screenshot); `gradlew`+`adb`+`logcat` build loop; model-agnostic `LlmClient`
  with a concrete Claude impl (default `claude-opus-4-8`).

## Conventions (see `.claude/CONVENTIONS.md`)
- App id `com.assist`; single `:app` module; minSdk 30 / target/compile 35; JDK 17.
- Kotlin, Compose, Hilt, Room, Coroutines, kotlinx.serialization.

## Environment (provisioned)
- JDK 17.0.19 (Temurin) at `~/Library/Java/JavaVirtualMachines/temurin-17.jdk`
  (resolved via `/usr/libexec/java_home -v 17`).
- Android SDK `~/Library/Android/sdk`: platform-tools **37.0.0**, build-tools
  35.0.0, platform android-35, emulator 36.6.11, system image
  `system-images;android-35;google_apis_playstore;arm64-v8a`.
- Emulator AVD: **`pixel7pro_api35`** (Pixel 7 Pro, Android 15, Play image).

## Progress

### Phase 00 — Decisions ✅
Feasibility confirmed; decisions locked (`.claude/phases/phase-00-decisions.md`).

### Phase 01 — Tooling & build/debug self-loop ✅
- Installed JDK 17 (Temurin), upgraded adb/platform-tools to 37.0.0, installed
  API-35 platform + build-tools 35 + emulator + Pixel Play system image.
- Created AVD `pixel7pro_api35`.
- Added `scripts/` build loop: `env.sh` (shared resolver), `build.sh`,
  `install.sh`, `run.sh`, `logcat.sh`, `emulator.sh`, `enable-service.sh`,
  `devices.sh`.
- Added `.gitignore`, `local.properties` (gitignored), root `README.md`, this log.

### Phase 02 — App skeleton ✅
- **Gradle project** (Kotlin DSL): `settings.gradle.kts`, root + `:app`
  `build.gradle.kts`, `gradle.properties`, version catalog
  `gradle/libs.versions.toml` (AGP 8.7.3, Gradle 8.9, Kotlin 2.0.21, Compose BOM
  2024.10.01, Hilt 2.52, Room 2.6.1, coroutines/serialization/security-crypto).
  Wrapper committed (`./gradlew`, pinned 8.9).
- **App** under `com.assist`: `AssistApplication` (`@HiltAndroidApp`),
  `ui.MainActivity` (Compose) onboarding — live permission status + deep-links
  (accessibility, overlay, mic, notifications), masked Anthropic-key field, gated
  "Start session". `data.SecretStore` + `EncryptedSecretStore`
  (EncryptedSharedPreferences), `di.AppModule`, `ui.Permissions` helper,
  Material3 theme. Package skeleton stubs for the parallel phases
  (`service/ llm/ llm/anthropic/ agent/ voice/ overlay/ prompt/`).
- **Manifest**: permissions + commented placeholder registrations for the
  phase-03 AccessibilityService and phase-07 overlay FGS.
- **Verified**: `:app:assembleDebug` + `:app:testDebugUnitTest` green; installed
  and launched on `pixel7pro_api35` (onboarding screen renders, no crash).
- **Build automation**: inner loop is now **Gradle tasks** (`gradle/device.gradle.kts`:
  `runApp`/`launchApp`/`stopApp`/`enableAccessibility`/`listDevices`, plus AGP
  `installDebug`); `build.sh`/`install.sh`/`run.sh` thinned to wrappers.
  `emulator.sh`/`logcat.sh`/`enable-service.sh` stay scripts (streaming/branching).
- **Portability**: no machine-specific bakes committed; `env.sh` is OS-aware
  (macOS/Linux SDK + JDK discovery) and auto-generates gitignored
  `local.properties` from the resolved SDK on first build.

### Phase 03 — Accessibility service + device tools ✅
- `service.AssistAccessibilityService` (singleton `instance`, `@AndroidEntryPoint`)
  registered in the manifest + `res/xml/accessibility_service_config.xml` (retrieve
  content, gestures, screenshot). Emits `ScreenChangeSignals.events` on window
  state/content changes so the agent loop can await UI settle.
- `ScreenSerializer` walks the a11y tree — framework-free via a `NodeView`
  abstraction — into `ScreenState`/`UiElement` with a stable per-frame `id`→node
  map (`ScreenFrame`). Caps 150 elements / 200-char text; recycles nodes (skipped
  immediately, retained on `frame.recycle()`) to avoid ANRs.
- `DeviceController` (interface + `DefaultDeviceController`): `getScreenState`,
  `takeScreenshot`, tap/longPress (+xy), swipe/scroll (id or direction), setText,
  pressKey (BACK/HOME/RECENTS/NOTIFICATIONS/QUICK_SETTINGS/ENTER), openApp (label
  resolution via `AppResolver`), wait — all `suspend`, return `ToolOutcome`.
- DI in new `di/ServiceModule.kt` (AppModule untouched). Debug hook:
  `com.assist.DEBUG_DUMP_SCREEN` broadcast dumps/acts for verification.
- Verified on emulator: Settings dump (27 elements, sensible bounds/flags),
  tap-by-id navigation + swipe change the screen, screenshot non-null 1440x3120.
  29 unit tests green (serializer mapping/caps/recycle, bounds/center, swipe
  geometry, label resolution, rendering).

### Phase 04 — LLM abstraction + Claude client ✅
- **Seeded seam** (`com.assist.llm`, on `main` before the fan-out so 04/05 shared
  it): `LlmClient`, `LlmRequest/Response`, `LlmMessage`+`Role`, `ContentBlock`
  (Text/Image/Thinking/ToolUse/ToolResult), `SystemBlock`, `ToolDef`, `ToolCall`,
  `LlmStreamEvent`, `Usage`, `Effort`. Model-agnostic; no Anthropic types leak.
- **Decision: direct REST via OkHttp + kotlinx.serialization** (not the
  `anthropic-java` SDK) — provable minSdk-30 fit (no desugaring), direct
  `call.cancel()` for interruptibility, beta features are just headers+JSON.
  Rationale in `llm/anthropic/README.md`.
- `AnthropicLlmClient`: SSE streaming w/ cooperative cancel, tool_use/tool_result
  round-trip, vision (base64), adaptive thinking + `output_config.effort`, prompt
  caching (`cache_control` on system+tools; cache tokens surfaced in `Usage`),
  `countTokens`, `dropOldScreenshots`/`compactConversation`
  (`clear_tool_uses_20250919` / `compact_20260112`), typed retryable errors.
  `ModelRouter` (SIMPLE→haiku-4-5, STANDARD→sonnet-5, else opus-4-8). Additive
  seam field `LlmRequest.contextManagement`. New `di/LlmModule.kt`.
- **Real-model smoke test** in `androidTest` (`AnthropicSmokeTest`), key-gated via
  `BuildConfig.ANTHROPIC_API_KEY` (from `-PanthropicApiKey` / `ANTHROPIC_API_KEY`
  env); skips cleanly with no key. **Not yet run live** — needs the user's key.
- Added deps: `okhttp 4.12.0`, `androidx-test-runner`; `buildConfig=true`.

### Phase 05 — Session DB + context management ✅
- Room DB (`AssistDatabase`, v1, destructive migration): `Session/Message/
  ToolCall/Usage/Media/Note` entities + DAOs. Screenshots stored as **files**
  (`filesDir/screenshots/session_<id>/<uuid>.<ext>`) via `ScreenshotStore`; rows
  hold paths only (no base64 blobs). `StoredBlock`/`MessageContentCodec` for
  on-disk content.
- `SessionRepository`: CRUD + `listSessions(): Flow`, append message/toolcall/
  usage, notes, `saveScreenshot`, and `buildLlmMessages(id): List<LlmMessage>`
  (rebuilds against the phase-04 seam; dropped screenshots → placeholder text).
  `CostCalculator` (opus-4-8 $5/$25, sonnet-5 $3/$15, haiku-4-5 $1/$5; cache
  1.25×/0.1×), `ContextTracker`→`ContextStatus`. Context-shrink:
  `markScreenshotsDropped`, `summarizeAndCompact`. New `di/DataModule.kt`.
- 14 unit tests (in-memory Room + Turbine). Added test dep `turbine`.

### Integration ✅
- Merged 03/04/05 to `main` (`--no-ff`); only conflicts were the two build files
  (union-resolved: turbine + test-runner + okhttp all kept). Each phase added its
  own `di/*Module.kt`; `AppModule` untouched.
- **Integrated build green**: `:app:assembleDebug`, `:app:testDebugUnitTest`
  (**43 tests**), `:app:compileDebugAndroidTestKotlin` all pass; app installs +
  launches on `pixel7pro_api35` with the merged Hilt graph (4 modules), no DI/
  runtime crash.

### Phase 04 live smoke test ✅ (human checkpoint cleared)
- Ran `:app:connectedDebugAndroidTest` for `AnthropicSmokeTest` against the real
  Claude API on `pixel7pro_api35`: **3/3 pass** (text streaming, tool-use
  round-trip, vision) — `tests=3 failures=0 errors=0 skipped=0`.
- **Bug caught + fixed:** the manifest never declared `INTERNET` — every network
  call was denied (`SecurityException`). Added `INTERNET` +
  `ACCESS_NETWORK_STATE`.
- **Emulator gotcha:** the running AVD had a dead DNS proxy (`UnknownHostException`
  though raw-IP ping worked). Fix = relaunch with `-dns-server 8.8.8.8,8.8.4.4`.

### Phase 06 — Agent orchestration loop (+ phase-12 seams) ✅
- **`com.assist.agent`** spine: `AgentTools` catalog (18 `ClientTool`s), `ToolRouter`
  (control/perception→`DeviceController`; `say`/`ask`/`finish`→`UserIo`; context
  tools→`SessionRepository`+context edits; `get_screen_state`/`take_screenshot`→
  capture+`tool_result`), `ActionGate` (pure classify + confirm; SEND/PAY/DELETE/
  INSTALL/CALL/PASSWORD via target-element keywords + allowlist), `AgentLoop`
  (cancellable coroutine; auto-perception; loop guards: max-steps, no-progress,
  context-ceiling; `interrupt()`), `AgentEventBus` (`SharedFlow<AgentEvent>`),
  `AgentService` (specialUse FGS), `DebugRunReceiver` (`com.assist.DEBUG_RUN` /
  `DEBUG_INTERRUPT`). `UserIo`→`LoggingUserIo` stub, `SystemPromptProvider`→real
  phase-10 impl (see Integration). New `di/AgentModule.kt`; `AppModule` untouched.
- **Phase-12 seams folded in (additive):** `ToolSpec` sealed (`ClientTool`/
  `ProviderTool`); `ToolDef` now a `typealias` → `ToolSpec.ClientTool` (zero churn);
  `LlmRequest.tools: List<ToolSpec>`; `Role.SYSTEM` threaded through
  `buildLlmMessages` + Anthropic mapper (→`{"role":"system"}`); `Speed{STANDARD,FAST}`
  on `LlmRequest`; `usage.speed`/`LlmResponse.speed`; fast-mode request-plumbing
  (`speed:"fast"`+`fast-mode-2026-02-01` beta on opus-4-8/4-7 only); `SystemTurnPlacement`
  legality check (§5 hooks). Memory tool / recipes / fast UI / SessionSteering left
  for the follow-on Phase 12.
- **70 unit tests green** (43 prior + `ActionGate` 14, `SystemTurnPlacement` 7,
  `AnthropicRequestFactory` 6).
- **Live e2e on `emulator-5554`** (real opus-4-8, key baked via BuildConfig): the
  `DEBUG_RUN` intent "open the Clock app and start a 1 minute timer" completed via
  real `open_app`+`tap`+`set_text` calls (screenshot: a running 1m timer counting
  down), all persisted to Room. `interrupt()` unwound the loop in **493ms**. A gated
  tap on the dialer Call button fired the confirmation (`ActionGate: gated tap [CALL]
  -> APPROVED`) before executing.

### Phase 08/09 — Voice research + swappable STT/TTS/wake seam ✅ (design)
- `.claude/voice-architecture.md`: read the `SpeechRecognizer`/`TextToSpeech`
  reference pages and captured findings (SpeechRecognizer is single-shot — fine for
  `ask()`, unfit for always-on wake; OS hotword is gated to the default-assistant
  role → sideload needs a dedicated on-device detector; TextToSpeech barge-in via
  `stop()`/`QUEUE_FLUSH`, `onRangeStart` for caption sync). Designed a
  provider-agnostic seam (`SttEngine`/`TtsEngine`/`WakeWordDetector`/
  `AudioSessionArbiter`/`VoiceProvider`) so the backend swaps between the Android
  APIs, cloud STT/TTS, OpenAI/Gemini realtime duplex, or on-device libs. Phase-08/09
  specs wired to it.

### Phase 11 — Device deploy & debug (Pixel 7 Pro) ✅
- New device scripts, all sourcing `env.sh` + honoring `ANDROID_SERIAL`, artifacts
  to gitignored `captures/`: `deploy-pixel.sh` (one-command build+install+launch
  via Gradle `runApp`; `--a11y`), `pair-device.sh` (Wi-Fi `pair`/`connect`/`usb`
  wireless debugging — no IPs baked in), `dump-a11y.sh` (fires the phase-03
  `com.assist.DEBUG_DUMP_SCREEN` broadcast + prints the logged outline;
  `--screenshot/--open/--tap/--swipe/--key`), `screenshot.sh` (`exec-out
  screencap`), `bugreport.sh` (`full`/`--anr`/`--logcat`). Enhanced `devices.sh`
  to emit copy-ready `export ANDROID_SERIAL=…` lines per online device.
- **`DEVICE.md`**: fresh-Pixel first-run walkthrough — Developer Options + USB
  debugging, USB/Wi-Fi connect, one-command deploy, the human permission grants
  (Accessibility "full control", overlay, mic, notifications), reading logs
  (tag table incl. future `AgentLoop`), crash symbolication (pure-JVM, no NDK;
  minify off), real-app validation checklist (Clock/Messages/Maps/Gmail) + the
  flagship "find my next flight and start navigation" eval, release-ish signing
  notes (debug keystore is the sideload path; optional gitignored `*.jks`).
- README updated with the device workflow + helper table pointing at `DEVICE.md`.
- No app source / Gradle changes; deploy uses the existing `runApp` task and the
  default debug keystore. All scripts pass `bash -n`.

### Integration — 06 spine + 10 + 11 → `main` ✅
- Merged all three worktree branches (`--no-ff`). Conflicts were only the manifest
  (INTERNET comment — both sides had re-added the permission) and `CLAUDE.md`
  (union-resolved to keep every phase section).
- **`SystemPromptProvider` reconciliation:** the spine's `AgentLoop` consumes
  `agent.SystemPromptProvider(SystemPromptContext)`; phase-10 shipped the real
  prompt as `prompt.SystemPromptProvider(PromptContext)`. Bridged with
  `agent.Phase10SystemPromptProvider` (adapter, `@Inject` the phase-10 provider,
  maps `deviceInfo`→`deviceModel`); `AgentModule` now provides the adapter instead
  of `PlaceholderSystemPromptProvider`. User intent stays out of the system core
  (it's in the first user turn) so the cacheable prefix is byte-stable.
- **Verified on merged `main`:** `:app:assembleDebug` + `:app:testDebugUnitTest`
  green (**79 tests**, 0 fail); 6-module Hilt graph (App/Service/Llm/Data/Agent/
  Prompt) resolves; app launches with no crash. **Live e2e** (`DEBUG_RUN` "open the
  Clock app and start a 1 minute timer") completed in 8 steps with the *real*
  phase-10 prompt — a 1-minute timer counts down on-screen.
- **Gotcha:** the `DEBUG_RUN` broadcast must target the package
  (`am broadcast -p com.assist -a com.assist.DEBUG_RUN --es intent "…"`); an
  implicit broadcast is dropped on API 35. Reinstalling also unbinds the
  accessibility service — re-enable it before an e2e run.

### Phase 08 — Voice I/O + Interruptibility ✅ (on worktree; device checkpoint pending)
- **Swappable seam** (`com.assist.voice`, from `.claude/voice-architecture.md`):
  `SttEngine` (+`SttEvent`/`SttResult`/`SttConfig`/`SttError`/`SttException`),
  `TtsEngine` (+`TtsEvent`/`SpeakOptions`/`VoiceInfo`), `AudioSessionArbiter`+
  `MicOwner`, `VoiceProvider`+`VoiceProviderKind`, `WakeWordDetector` (declared for
  phase-09; provider returns null), `TypedReplySource` (phase-07 hook). No backend
  types leak past the interfaces.
- **`android` provider** (`voice/android`, `id="android"`, `kind=PIPELINE`):
  `AndroidSttEngine` over `SpeechRecognizer` — prefers `createOnDeviceSpeechRecognizer`
  (API 31+) + `EXTRA_PREFER_OFFLINE`, all calls marshaled to the main thread,
  `transcribeOnce()` (for `ask`) + `stream():Flow<SttEvent>` (partials/rms/endpoint);
  `AndroidTtsEngine` over `TextToSpeech` — init gated on `OnInitListener` SUCCESS,
  `say()` suspends to `onDone` via `UtteranceProgressListener`, coroutine-cancel →
  `stop()` (barge-in), `USAGE_ASSISTANT` + transient-duck audio focus, `onRangeStart`
  → `TtsEvent.Range` for caption sync; `AndroidVoiceProvider` bundles them.
  `SttErrorMapper` (framework-free `ERROR_*`→`SttError`), `AudioFocus` helper.
- **`DefaultAudioSessionArbiter`** — framework-free, one mic owner; higher priority
  (`BARGE_IN>LISTEN_ONCE>WAKE_WORD`) preempts (cancels the holder's block), equal/
  lower parks on release + retries. **`BargeInDetector`** — `AudioRecord`+`EnergyVad`
  gate while TTS plays → `tts.stop()`+`agentLoop.interrupt()`+`transcribeOnce()` at
  `BARGE_IN`, redirect via callback (device-only).
- **`VoiceUserIo : UserIo`** (the real impl): `say`→TTS (emits `Speaking`),
  `ask`→say then `transcribeOnce` at `LISTEN_ONCE` (emits `Listening`); optional
  `TypedReplySource` races typed vs. spoken (phase-07); recognition failures degrade
  to an empty answer (loop never crashes).
- **DI collision resolved:** removed `provideUserIo` from `AgentModule`; new
  `di/VoiceModule.kt` binds `VoiceUserIo`→`UserIo` (+ engines, arbiter, provider,
  barge-in detector). `LoggingUserIo` kept as a headless/test fallback. `AppModule`
  untouched.
- **Manual test screen** (`ui/VoiceTestActivity`+`VoiceTestScreen`+`VoiceTestViewModel`):
  say / listen-once / hold-to-talk (push-to-talk) / run-a-task, reachable from the
  onboarding "Voice test" button (mic-gated). Exercises voice without wake-word.
- **Verified:** `:app:assembleDebug` + `:app:testDebugUnitTest` green (**104 tests**,
  +25: `SttErrorMapper` 10, `EnergyVad` 6, `DefaultAudioSessionArbiter` 4,
  `VoiceUserIo` 5 vs. fakes). `installDebug` + launch on `emulator-5554` clean — the
  7-module Hilt graph (App/Service/Llm/Data/Agent/Prompt/**Voice**) resolves, no
  duplicate-binding crash. Enabled `unitTests.isReturnDefaultValues` so framework-free
  logic tests run without Robolectric.
- **Deferred to device checkpoint:** live STT/TTS + real barge-in (emulator mic/STT
  unreliable). **Seam gaps for consumers:** phase-07 binds a `TypedReplySource` +
  drives captions off `TtsEvent.Range`/`SttEvent.Partial` + the overlay mic button;
  phase-09 implements `WakeWordDetector` and routes wake/listen/barge-in through the
  shared `AudioSessionArbiter`.


### Phase 07 — Overlay UI (interaction viz + interrupt) ✅
- **`com.assist.overlay`**: `OverlayService` (own `specialUse` FGS) adds a Compose
  overlay to `WindowManager` via `TYPE_APPLICATION_OVERLAY` +
  `FLAG_NOT_FOCUSABLE|NOT_TOUCH_MODAL` (driven app keeps input), toggling focusable
  only while capturing a typed reply; draggable; add/remove + focus/move via
  `WindowManager`. `OverlayLifecycleOwner` (manual Lifecycle/SavedStateRegistry/
  ViewModelStore) hosts the `ComposeView`.
- **`OverlayController`** (`@Singleton`, `@Inject`): collects the phase-06
  `AgentEventBus` into `StateFlow<OverlayUiState>` via a pure, unit-tested
  `OverlayReducer`; a leading+trailing `throttleLatest` coalesces high-frequency
  text deltas (snapshots are cumulative, so nothing is lost). DB-backed HUD refresh
  (`ContextTracker`); session controls (new/switch via `SessionRepository`),
  compact-now (`summarizeAndCompact`) / drop-screenshots-now
  (`markScreenshotsDropped`); interrupt → `AgentLoop.interrupt()`.
- **Typed-reply seam (for `voice/VoiceUserIo.ask()` merge):** the overlay never
  edits `UserIo`. It exposes `submitReply(text)` (UI/confirmation Yes-No),
  `suspend awaitTypedReply(): String`, and `typedReplies: Flow<String>`. Merge
  wiring should race `awaitTypedReply()` against speech in `VoiceUserIo.ask()`
  ("whichever returns first") — same adapter pattern as `SystemPromptProvider`.
- **Compose content**: draggable collapsed **bubble** (idle/listening/thinking/
  speaking/acting + stop affordance) and expanded **panel** (streaming assistant
  text, ordered `tool_call` chips w/ args + ✓/✗, thinking indicator, Yes/No
  confirmation → reply seam, HUD = context used/window + cost + screenshot count).
- **MainActivity/OnboardingScreen**: additive `OverlayCard` start/stops
  `OverlayService`, gated on the existing overlay permission. Manifest registers
  `OverlayService`; additive strings. No edits to `AgentService`/`AppModule`/
  `AgentModule`; no `OverlayModule` needed (all deps constructor-injectable).
- **FGS unification (integration point):** ships as its own `specialUse` FGS
  mirroring `AgentService`; both share the singleton `AgentEventBus` in-process.
  Intended to later merge into ONE coordinated FGS + notification — call-out for
  the merge; `AgentService` untouched here.
- **18 new unit tests** (OverlayReducer 10 — ordering/tool-chip results/HUD math;
  Throttle 4 — leading+trailing, no-drop; OverlayController 4 — event fold + HUD
  refresh + reply seam). Full suite **97 tests green**; `:app:assembleDebug` green.
- **Live on `emulator-5554`:** overlay draws over Clock (bubble + panel) while
  Clock stays the resumed/focused app (`fl=NOT_FOCUSABLE NOT_TOUCH_MODAL`); during
  a real `DEBUG_RUN` ("start a 2 minute timer", live opus-4-8) the panel streamed
  assistant text, showed the `open_app` ✓ chip, and the HUD read `2k/1.0M ctx ·
  $0.1096 · 0 shots`; the session list rendered real Room sessions. **Gotcha:**
  some Settings sub-pages set `HIDE_NON_SYSTEM_OVERLAY_WINDOWS`
  (`mForceHideNonSystemOverlayWindow=true`), which force-hides SAW overlays — test
  overlay draw-over against a benign app (Clock), not those Settings screens.


### Phase 07 + 08 integration ✅
- Merged overlay (07) + voice (08) to `main`; wired the overlay
  `TypedReplySource`/`awaitTypedReply()` into `VoiceUserIo.ask()` via
  `di/VoiceOverlayModule` (`OverlayController` injected as a `Provider` to break the
  `OverlayController → AgentLoop → UserIo → TypedReplySource` Dagger cycle).
- Wired the onboarding **Start session** button: intent dialog →
  `AgentService.runIntent` (+ auto-show overlay). Verified on-device end-to-end.

### Phase 12 — Session UI + learned memory ✅ (merged + nav wired)
- Merged `com.assist.ui.sessions` (sessions list / transcript+context / recipes),
  `com.assist.memory.MemoryStore` (path-traversal-safe; memory tool in the **live**
  catalog + routed in `ToolRouter`), `data.TaskRecipeEntity`/`TaskMemoryRepository`,
  `CostCalculator` fast rows, fast-mode `SettingsStore`+toggle. **Real Room
  `Migration(1,2)`** (sessions preserved, not destructive; `exportSchema` on,
  `app/schemas/` committed). 163 unit tests green.
- **Nav shell:** `MainActivity` now hosts a bottom `NavigationBar` (Home / Sessions
  / Memory) over a `NavHost` (`home`, `sessions`, `session/{id}`, `recipes`). Added
  `androidx.navigation:navigation-compose` + `material-icons-core`. Verified
  on-device: Sessions lists the real run ("…2 minute timer · 15 msgs · $0.17"),
  transcript + Context&cost panel render.

### Phase 13 — CI & infra + signed release APK ✅ (on worktree)
- **Signed release APK (primary goal — no USB / no adb install path):**
  `app/build.gradle.kts` now has a `signingConfigs.release` that reads
  `ASSIST_KEYSTORE_FILE`/`ASSIST_KEYSTORE_PASSWORD`/`ASSIST_KEY_ALIAS`/
  `ASSIST_KEY_PASSWORD` from env vars **or** Gradle props (`assistKeystoreFile`…);
  CI decodes `ASSIST_KEYSTORE_BASE64`→file. Wired into `buildTypes.release`; if no
  secrets set it **falls back to the debug keystore** so `assembleRelease` always
  yields an installable APK. `isMinifyEnabled=false` kept. No `ANTHROPIC_API_KEY`
  baked into release (ships empty; read at runtime). Verified both paths locally:
  debug-fallback APK verifies as `CN=Android Debug`; with env vars set it verifies
  as the custom keystore cert. **`RELEASE.md`** documents keytool + base64 + the
  exact GitHub secrets + tag-to-install flow; `DEVICE.md` §9 updated.
- **GitHub Actions** (`.github/workflows/`): `ci.yml` (push `main` + PR → JDK17,
  Gradle cache, `assembleDebug testDebugUnitTest lintDebug detekt ktlintCheck`,
  uploads reports on failure); `release.yml` (`workflow_dispatch` + `v*` tag →
  signed `assembleRelease`, uploads `assist-<version>.apk` artifact + attaches to
  a GitHub Release on tags). `.github/dependabot.yml` (gradle + actions, weekly).
- **Static analysis (baselined — no reformat):** detekt 1.23.7
  (`config/detekt/detekt.yml` + `baseline.xml`) and ktlint plugin 12.1.1 / ktlint
  1.3.1 (`config/ktlint/baseline.xml`, root `.editorconfig`) added via the version
  catalog + applied in `:app`; both fold into `./gradlew check`. Android Lint block
  (`abortOnError`, `checkDependencies`, `app/lint-baseline.xml` — 52 grandfathered
  warnings). Room schema export + `1→2` migration test already landed in phase-12
  (left as-is).
- **Verified:** `assembleDebug`, `testDebugUnitTest` (163 tests), `detekt`,
  `ktlintCheck`, `lintDebug`, `./gradlew check`, and `assembleRelease` all green
  locally. No app source/logic touched (infra only).
- **Human checkpoint:** to get *real* signed releases (not debug-fallback), the
  user must create the 4 `ASSIST_*` GitHub secrets (+ optional `ASSIST_KEYSTORE_BASE64`)
  and keep the keystore (see RELEASE.md). CI emulator job / `ANTHROPIC_API_KEY`
  secret left out by design.

### Next
- **Phase 09 (wake word)** — Porcupine/openWakeWord `WakeWordDetector` in an FGS,
  coordinated via the shared `AudioSessionArbiter`.
- **Follow-ups:** unify `AgentService`+`OverlayService` into one coordinated FGS;
  `SessionSteering` barge-in/budget wiring; `AgentService` optional existing-
  `sessionId` for one-tap session resume.

## Notes / gotchas
- Homebrew `openjdk@17` won't bottle on this machine (needs full Xcode); used a
  prebuilt Temurin tarball into `~/Library/Java/JavaVirtualMachines` instead.
- The Pixel 7 Pro AVD profile required updating `cmdline-tools;latest` (the
  bundled catalog only went to Pixel 5).
- `brew install gradle` builds `gettext` from source here (no bottle, same
  CommandLineTools-only issue as the JDK) — too slow. Bootstrapped the wrapper
  instead by downloading the Gradle 8.9 dist zip directly and running
  `gradle wrapper`. The committed wrapper means no system Gradle is needed again.
