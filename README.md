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

## Portability (works on any machine)

Nothing machine-specific is committed. A fresh clone needs only:

1. **JDK 17** on the machine (Temurin recommended). `scripts/*` auto-discover it
   via `/usr/libexec/java_home -v 17` (macOS) or `/usr/lib/jvm/*17*` (Linux);
   override with `ASSIST_JAVA_HOME=/path/to/jdk17`.
2. **Android SDK** with `platform-android-35` + `build-tools;35.0.0`. Point to it
   with `ANDROID_HOME` (defaults: `~/Library/Android/sdk` on macOS,
   `~/Android/Sdk` on Linux).

Everything else is self-contained: the **Gradle wrapper** (`./gradlew`, pinned to
8.9) is committed, so no system Gradle is required; `local.properties` is
**gitignored** and auto-generated from the resolved SDK path on first build;
plugin/library versions are pinned in `gradle/libs.versions.toml`; the debug
signing key is the per-machine default. Run `scripts/build.sh` (or Android Studio)
on a clean checkout and it just works.

## Build / run / debug loop

The **inner loop is Gradle tasks** (single source of truth); shell scripts are
thin wrappers that resolve the JDK/SDK/device and delegate.

```bash
# 1. Start the emulator (or plug in a device with USB debugging)
scripts/emulator.sh              # boots AVD pixel7pro_api35; --headless for CI
./gradlew listDevices            # or scripts/devices.sh — export ANDROID_SERIAL to pick one

# 2. Build + install + launch  (Gradle tasks; scripts wrap these)
./gradlew :app:assembleDebug     # = scripts/build.sh
./gradlew :app:installDebug      # = scripts/install.sh
./gradlew runApp                 # install + launch MainActivity  = scripts/run.sh
./gradlew stopApp                # force-stop

# 3. Watch logs  (streaming — stays a script)
scripts/logcat.sh                # filtered to the app process

# 4. Accessibility service (needed from phase-03 on)
./gradlew enableAccessibility    # emulator/rooted: sets the secure setting
scripts/enable-service.sh        # device: opens Settings for a manual toggle
```

### Gradle vs. scripts — the split

| Concern | Where | Why |
|---|---|---|
| assemble / install / uninstall | Gradle (AGP built-ins) | Native to the build |
| `runApp` / `launchApp` / `stopApp` / `enableAccessibility` / `listDevices` | Gradle (`gradle/device.gradle.kts`) | One-shot, dependency-aware |
| Emulator boot + boot-poll | `scripts/emulator.sh` | Backgrounds a process; poor daemon fit |
| Streaming `logcat -f` | `scripts/logcat.sh` | Long-running tail |
| Accessibility toggle on locked devices | `scripts/enable-service.sh` | Device-vs-emulator branching |
| JDK/SDK/device resolution | `scripts/env.sh` | Shared bootstrap for the above |

### On a physical Pixel 7 Pro

Deploy to the phone (not just the emulator) in one command:

```bash
scripts/devices.sh                     # list/select — copy the export line
export ANDROID_SERIAL=<pixel-serial>   # only if >1 device attached
scripts/deploy-pixel.sh                # build + install + launch on the phone
```

Device-specific helpers (all honor `ANDROID_SERIAL`, write to gitignored `captures/`):

| Script | Purpose |
|---|---|
| `scripts/deploy-pixel.sh` | One-command build + install + launch (`--a11y` opens Accessibility settings after). |
| `scripts/pair-device.sh` | Wi-Fi pairing/connect (`pair`/`connect`/`usb`) for wireless debugging. |
| `scripts/dump-a11y.sh` | Trigger the phase-03 screen dump on device (perception check). |
| `scripts/screenshot.sh` | Pull a PNG screenshot for inspection. |
| `scripts/bugreport.sh` | Capture a bugreport zip / ANR traces / logcat snapshot. |

Full first-run walkthrough — pairing a fresh phone, the human permission grants
(Accessibility, Display over other apps, mic, notifications), the real-app
validation checklist, and crash symbolication — is in **[`DEVICE.md`](DEVICE.md)**.

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
