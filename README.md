# Assist — AI Phone Agent (Android / Kotlin)

An on-device AI assistant that drives an Android phone through the Accessibility
APIs to accomplish arbitrary user intents by voice. An LLM (Claude) perceives the
screen and emits **tool calls** that become gestures and spoken feedback.

Design & phased build plan live in [`.claude/`](.claude/README.md). This README
covers building, running, and debugging.

## Prerequisites (macOS, already provisioned by phase-01)

| Tool | Version | Location |
|------|---------|----------|
| JDK | **17** (Temurin 17.0.19) | `~/Library/Java/JavaVirtualMachines/temurin-17.jdk` (found via `/usr/libexec/java_home -v 17`) |
| Android SDK | — | `~/Library/Android/sdk` |
| platform-tools (adb) | **37.0.0** | `$ANDROID_HOME/platform-tools` |
| build-tools | 35.0.0 | |
| platform | android-35 | |
| emulator + system image | Pixel Play, API 35 arm64 | AVD `pixel7pro_api35` |

The build scripts resolve `ANDROID_HOME` and a JDK-17 `JAVA_HOME` automatically
(see `scripts/env.sh`). Override with `ASSIST_JAVA_HOME` / `ANDROID_HOME` /
`ANDROID_SERIAL` if needed.

> The app project itself (Gradle module `:app`) is created in **phase-02**; until
> then the build/install/run scripts intentionally error with a helpful message.

## Build / run / debug loop

```bash
# 1. Start the emulator (or plug in a device with USB debugging)
scripts/emulator.sh              # add --headless for CI-style boot
scripts/devices.sh               # list devices; export ANDROID_SERIAL to pick one

# 2. Build + install + launch
scripts/build.sh                 # ./gradlew :app:assembleDebug
scripts/install.sh               # build + adb install -r -g
scripts/run.sh                   # install + launch MainActivity

# 3. Watch logs
scripts/logcat.sh                # filtered to the app process

# 4. Accessibility service (needed from phase-03 on)
scripts/enable-service.sh        # emulator: sets it directly; device: opens Settings
```

Target a physical **Pixel 7 Pro** instead of the emulator by enabling USB
debugging and `export ANDROID_SERIAL=<serial>` (see `scripts/devices.sh`). Full
device deploy/debug workflow: [`.claude/phases/phase-11-device-deploy.md`](.claude/phases/phase-11-device-deploy.md).

## Secrets

The Anthropic API key is entered in-app and stored in
`EncryptedSharedPreferences` (phase-02). For automated real-model tests it is
read from the `ANTHROPIC_API_KEY` env var and is never committed.

## Repo layout

```
.claude/    design docs + phased implementation plan (source of truth)
scripts/    build/debug self-loop (env.sh is the shared resolver)
app/        the Android app module (from phase-02)
CLAUDE.md   running progress log
```
