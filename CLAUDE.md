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

### Phase 01 — Tooling & build/debug self-loop ✅ (in progress → wrapping)
- Installed JDK 17 (Temurin), upgraded adb/platform-tools to 37.0.0, installed
  API-35 platform + build-tools 35 + emulator + Pixel Play system image.
- Created AVD `pixel7pro_api35`.
- Added `scripts/` build loop: `env.sh` (shared resolver), `build.sh`,
  `install.sh`, `run.sh`, `logcat.sh`, `emulator.sh`, `enable-service.sh`,
  `devices.sh`.
- Added `.gitignore`, `local.properties` (gitignored), root `README.md`, this log.
- Scripts fail gracefully until phase-02 creates the Gradle project.

### Next
- **Phase 02 — App skeleton** (sequential): Gradle project, Compose/Hilt/Room,
  permission onboarding, `SecretStore`. Then fan out 03/04/05 in parallel.

## Notes / gotchas
- Homebrew `openjdk@17` won't bottle on this machine (needs full Xcode); used a
  prebuilt Temurin tarball into `~/Library/Java/JavaVirtualMachines` instead.
- The Pixel 7 Pro AVD profile required updating `cmdline-tools;latest` (the
  bundled catalog only went to Pixel 5).
