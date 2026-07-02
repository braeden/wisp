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

### Phase 06 — Agent orchestration loop (+ phase-12 seams) ✅ (on branch)
- **`com.assist.agent`** spine: `AgentTools` catalog (18 `ClientTool`s), `ToolRouter`
  (control/perception→`DeviceController`; `say`/`ask`/`finish`→`UserIo`; context
  tools→`SessionRepository`+context edits; `get_screen_state`/`take_screenshot`→
  capture+`tool_result`), `ActionGate` (pure classify + confirm; SEND/PAY/DELETE/
  INSTALL/CALL/PASSWORD via target-element keywords + allowlist), `AgentLoop`
  (cancellable coroutine; auto-perception; loop guards: max-steps, no-progress,
  context-ceiling; `interrupt()`), `AgentEventBus` (`SharedFlow<AgentEvent>`),
  `AgentService` (specialUse FGS), `DebugRunReceiver` (`com.assist.DEBUG_RUN` /
  `DEBUG_INTERRUPT`). `UserIo`→`LoggingUserIo` stub (08 swaps), `SystemPromptProvider`
  →`PlaceholderSystemPromptProvider` (10 swaps). New `di/AgentModule.kt`; `AppModule`
  untouched.
- **Phase-12 seams folded in (additive):** `ToolSpec` sealed (`ClientTool`/
  `ProviderTool`); `ToolDef` now a `typealias` → `ToolSpec.ClientTool` (zero churn);
  `LlmRequest.tools: List<ToolSpec>`; `Role.SYSTEM` threaded through
  `buildLlmMessages` + Anthropic mapper (→`{"role":"system"}`); `Speed{STANDARD,FAST}`
  on `LlmRequest`; `usage.speed`/`LlmResponse.speed`; fast-mode request-plumbing
  (`speed:"fast"`+`fast-mode-2026-02-01` beta on opus-4-8/4-7 only); `SystemTurnPlacement`
  legality check (§5 hooks). Memory tool / recipes / fast UI / SessionSteering left
  for the follow-on Phase 12.
- **Fix:** added the missing `INTERNET`/`ACCESS_NETWORK_STATE` permissions to the
  manifest (absent on this branch base; the loop's HTTP needs them).
- **70 unit tests green** (43 prior + `ActionGate` 14, `SystemTurnPlacement` 7,
  `AnthropicRequestFactory` 6). `:app:assembleDebug` + `compileDebugAndroidTestKotlin`
  green.
- **Live e2e on `emulator-5554`** (real opus-4-8, key baked via BuildConfig): the
  `DEBUG_RUN` intent "open the Clock app and start a 1 minute timer" completed via
  real `open_app`+`tap`+`set_text` calls (screenshot: a running 1m timer counting
  down), all persisted to Room. `interrupt()` unwound the loop in **493ms**. A gated
  tap on the dialer Call button fired the confirmation (`ActionGate: gated tap [CALL]
  -> APPROVED`) before executing.

### Next
- **Human checkpoint:** run the phase-04 live smoke test with a real
  `ANTHROPIC_API_KEY` (text + tool-use + vision turns) to confirm the Claude
  client against the API.
- **Phase 07 (overlay)** and **08 (voice)** run in parallel — both consume
  `AgentEventBus`; 08 provides the real `UserIo`. **Phase 10** swaps in the real
  `SystemPromptProvider`.
- **Phase 12** (task memory / fast-mode UI / SessionSteering barge-in) queued as a
  follow-on extension building on the seams landed here.

## Notes / gotchas
- Homebrew `openjdk@17` won't bottle on this machine (needs full Xcode); used a
  prebuilt Temurin tarball into `~/Library/Java/JavaVirtualMachines` instead.
- The Pixel 7 Pro AVD profile required updating `cmdline-tools;latest` (the
  bundled catalog only went to Pixel 5).
- `brew install gradle` builds `gettext` from source here (no bottle, same
  CommandLineTools-only issue as the JDK) — too slow. Bootstrapped the wrapper
  instead by downloading the Gradle 8.9 dist zip directly and running
  `gradle wrapper`. The committed wrapper means no system Gradle is needed again.
