# DEVICE.md — Deploy & debug Assist on a physical Pixel 7 Pro

The emulator (`scripts/emulator.sh`, AVD `pixel7pro_api35`) is the fast inner
loop. This doc covers the **physical Pixel 7 Pro**: first-time pairing, the
one-command deploy, granting the on-device permissions Assist needs, streaming
logs, and the debug helpers for when a run misbehaves.

Everything here is portable — no serials, IPs, or machine paths are committed.
The scripts resolve the SDK/JDK via `scripts/env.sh` and pick a device via
`ANDROID_SERIAL` (or the single online device), exactly like the emulator loop.

- [TL;DR — deploy in one command](#tldr)
- [1. One-time phone setup (human)](#1-one-time-phone-setup-human)
- [2. Connect the phone (USB or Wi-Fi)](#2-connect-the-phone-usb-or-wi-fi)
- [3. Deploy Assist](#3-deploy-assist)
- [4. Grant the on-device permissions (human)](#4-grant-the-on-device-permissions-human)
- [5. Run your first session](#5-run-your-first-session)
- [6. Debug helpers](#6-debug-helpers)
- [7. Reading the logs & crash symbolication](#7-reading-the-logs--crash-symbolication)
- [8. Real-app validation checklist](#8-real-app-validation-checklist)
- [9. Release-ish build & signing](#9-release-ish-build--signing)
- [Troubleshooting](#troubleshooting)

<a name="tldr"></a>
## TL;DR — deploy in one command

Phone paired and USB-debugging authorized (steps 1–2 below), then:

```bash
scripts/devices.sh                          # find the Pixel; copy its export line
export ANDROID_SERIAL=<pixel-serial>        # only needed if >1 device is attached
scripts/deploy-pixel.sh                     # build + install + launch, one shot
scripts/enable-service.sh                   # opens Accessibility settings to toggle Assist on
scripts/logcat.sh                           # stream app logs
```

`scripts/deploy-pixel.sh` builds the debug APK and installs+launches it via the
Gradle `runApp` task. Add `--a11y` to jump straight to the Accessibility screen
after launch: `scripts/deploy-pixel.sh --a11y`.

---

<a name="1-one-time-phone-setup-human"></a>
## 1. One-time phone setup (human)

These are **manual, one-time** steps on the phone — they cannot be automated on a
non-rooted Pixel.

1. **Enable Developer Options.**
   Settings > About phone > **Build number** — tap it 7 times until it says
   "You are now a developer". Enter your PIN if prompted.
2. **Enable USB debugging.**
   Settings > System > **Developer options** > toggle **USB debugging** on.
3. *(optional)* **Enable Wireless debugging** in the same Developer options screen
   if you want to deploy over Wi-Fi (step 2b below).
4. *(recommended)* Turn on **Stay awake** (Developer options) so the screen doesn't
   sleep mid-session while plugged in.

> Checkpoint: **"Allow USB debugging?"** dialog. The first time you plug into a
> new computer, the phone shows this dialog with the host's RSA fingerprint. Tap
> **Always allow from this computer** > **Allow**. Until you do, `adb devices`
> shows the device as `unauthorized`.

<a name="2-connect-the-phone-usb-or-wi-fi"></a>
## 2. Connect the phone (USB or Wi-Fi)

### 2a. USB (simplest)

Plug in a data-capable USB-C cable, accept the authorization dialog, then:

```bash
scripts/devices.sh
```

You should see the Pixel listed as `device` (not `unauthorized` / `offline`),
with a serial and `model:Pixel_7_Pro`. Copy its printed `export ANDROID_SERIAL=…`
line if more than one device is attached.

### 2b. Wireless debugging (Android 11+, no cable)

On the phone: Settings > System > Developer options > **Wireless debugging** > on.
Two flows, both via `scripts/pair-device.sh` (nothing is hardcoded — you pass the
address the phone shows):

```bash
# One-time pairing: tap "Pair device with pairing code" on the phone. It shows an
# IP:PORT and a 6-digit code. That PORT is the *pairing* port.
scripts/pair-device.sh pair 192.168.1.42:37199 314159   # or omit the code to be prompted

# Then connect using the IP:PORT from the *main* Wireless debugging screen
# (this port differs from the pairing port):
scripts/pair-device.sh connect 192.168.1.42:41255
export ANDROID_SERIAL=192.168.1.42:41255
```

**USB → Wi-Fi shortcut** (skip pairing): plug in over USB once, then

```bash
scripts/pair-device.sh usb           # flips the phone to tcpip:5555 and connects over Wi-Fi
# unplug USB; the phone stays connected. Set ANDROID_SERIAL to the printed ip:5555.
```

<a name="3-deploy-assist"></a>
## 3. Deploy Assist

```bash
scripts/deploy-pixel.sh
```

This runs Gradle `runApp` (`:app:installDebug` then `am start` the launcher) on
the selected device. First run installs; subsequent runs do an incremental
reinstall. If several devices are attached and you didn't set `ANDROID_SERIAL`,
it stops and asks you to pick one — run `scripts/devices.sh`.

<a name="4-grant-the-on-device-permissions-human"></a>
## 4. Grant the on-device permissions (human)

Assist's onboarding screen (MainActivity) shows live status for each of these and
deep-links to the right settings page. You can also drive them from the shell:

| Permission | Why | How |
|---|---|---|
| **Accessibility service** ("Assist") | perceive the screen + perform gestures — the core capability | `scripts/enable-service.sh` opens Settings > Accessibility. Toggle **Assist** on, confirm the "full control" dialog. |
| **Display over other apps** | the voice/overlay UI (phase-07) floats over other apps | In-app deep-link, or Settings > Apps > Assist > **Display over other apps** > Allow. |
| **Microphone** | voice input | Granted on first use, or Settings > Apps > Assist > Permissions > **Microphone** > Allow. |
| **Notifications** | the foreground-service notification for the overlay/agent | Granted on first launch (Android 13+), or Settings > Apps > Assist > Notifications. |

> Checkpoint: the **Accessibility "full control" dialog**. Android warns that the
> service can observe your actions and act on your behalf. This is expected —
> Assist drives the phone through the Accessibility APIs. Tap **Allow**.

On a **non-rooted Pixel**, `scripts/enable-service.sh` cannot flip the secure
setting silently (that needs root, which the emulator has and a retail phone does
not), so it opens the Accessibility settings page for the manual toggle. On the
emulator, the same script sets it directly.

<a name="5-run-your-first-session"></a>
## 5. Run your first session

1. `scripts/deploy-pixel.sh --a11y` — deploy, then land on the Accessibility page;
   toggle **Assist** on.
2. Grant the remaining permissions (step 4). The onboarding screen's "Start
   session" button un-gates once all required grants are green.
3. Enter your **Anthropic API key** in the app (stored in
   `EncryptedSharedPreferences`; never logged, never committed).
4. Start a session and give a simple instruction (e.g. "open the Clock app").
5. In another terminal: `scripts/logcat.sh` to watch perception + tool calls.

<a name="6-debug-helpers"></a>
## 6. Debug helpers

All honor `ANDROID_SERIAL` and write artifacts to the gitignored `captures/` dir.

| Command | What it does |
|---|---|
| `scripts/logcat.sh` | Stream logs filtered to the app process (falls back to `Assist*` + `AndroidRuntime:E` tags when the app isn't running). |
| `scripts/dump-a11y.sh` | Fire the phase-03 `com.assist.DEBUG_DUMP_SCREEN` broadcast and print the serialized screen outline the service logs. Flags: `--screenshot`, `--open "<label>"`, `--tap <id>`, `--swipe up\|down\|left\|right`, `--key BACK\|HOME\|RECENTS\|…`. Requires the app installed **and** its Accessibility service enabled. |
| `scripts/screenshot.sh [out.png] [--open]` | Pull a PNG screenshot (`adb exec-out screencap`) for inspection. |
| `scripts/bugreport.sh` | Full `adb bugreport` zip. `--anr` pulls `/data/anr` traces only; `--logcat` dumps the current buffer to a file. |

Example — inspect what Assist "sees" on the current screen, with a screenshot:

```bash
scripts/dump-a11y.sh --screenshot
scripts/screenshot.sh --open
```

<a name="7-reading-the-logs--crash-symbolication"></a>
## 7. Reading the logs & crash symbolication

**Log tags** (tag = class simple name, per `CONVENTIONS.md`):

| Tag | Source | What you'll see |
|---|---|---|
| `AssistA11yService` | `service.AssistAccessibilityService` | service connect/teardown; the `==== SCREEN DUMP ====` outline from `dump-a11y.sh`. |
| `DeviceController` | `service.DefaultDeviceController` | tool actions (tap/swipe/setText/openApp) and their `ToolOutcome`. |
| `AssistApplication` | app startup | process/Hilt init. |
| `AgentLoop` | agent orchestration (**phase-06**, once landed) | per-turn tool calls, model routing, safety-gate confirmations. |
| `AndroidRuntime` | Android | uncaught-exception stack traces (crashes). |

Filter to a crash quickly:

```bash
scripts/logcat.sh AndroidRuntime:E "*:S"     # crashes only
scripts/bugreport.sh --logcat                # snapshot the buffer to captures/ for sharing
```

**Crash symbolication.** The app is pure Kotlin/JVM with no NDK/native code, so
`AndroidRuntime` stack traces are already symbolicated (real class/method/line
names) — no `ndk-stack`/`addr2line` step. R8/minify is **off** for `debug`
(and currently for `release` too — see build.gradle.kts), so there's no mapping
file to de-obfuscate against. If you later enable minify for a release build, run
traces through `$ANDROID_HOME/tools/proguard/bin/retrace` with the generated
`app/build/outputs/mapping/release/mapping.txt`.

<a name="8-real-app-validation-checklist"></a>
## 8. Real-app validation checklist

A repeatable manual pass confirming perception + one representative action on real
apps. Run each, watching `scripts/logcat.sh`. Use `scripts/dump-a11y.sh` to
confirm perception independently of the agent, and `scripts/screenshot.sh` to
capture evidence.

| App | Perceive (dump names ≥ a few real elements) | Representative action |
|---|---|---|
| **Clock** | tabs (Alarm/Clock/Timer) visible in the dump | "set a timer for 1 minute" |
| **Messages** | conversation list / compose field perceived | "open my latest conversation" (no send — safety gate) |
| **Maps** | search box perceived | "search for coffee near me" |
| **Gmail** | inbox rows perceived | "open the top email" (read-only) |

**Acceptance:** Assist perceives ≥ 3 of these and performs the action on each.

**Flagship scenario (tracked manual eval):** *"find my next flight and start
navigation."* Run it end-to-end with the **user watching** — it touches real
accounts/apps and the safety-confirmation gates. Capture the run:

```bash
scripts/logcat.sh | tee "captures/flagship-$(date +%Y%m%d-%H%M%S).log"   # in one terminal
scripts/screenshot.sh   # at each key step
# if it misbehaves:
scripts/bugreport.sh
```

Expect to iterate — log the failure point (which tool call, what the screen dump
showed) and re-run.

<a name="9-release-ish-build--signing"></a>
## 9. Release-ish build & signing

For a **personal sideload target**, the debug APK is the pragmatic "release"
build: `scripts/deploy-pixel.sh` ships it, signed with the default per-machine
**debug keystore** (`~/.android/debug.keystore`, auto-created by the SDK). Nothing
to configure, and reinstalls-over-the-top just work because the signer is stable
per machine.

If you want a **personal release keystore** (e.g. a stable identity across
machines, or to try `release` with minify):

```bash
keytool -genkey -v -keystore assist-release.jks -alias assist \
  -keyalg RSA -keysize 2048 -validity 10000
```

Keep it **out of git** — `*.jks` / `*.keystore` are already in `.gitignore`. Wire
it into `app/build.gradle.kts` via a `signingConfigs { create("release") { … } }`
that reads the passwords from `~/.gradle/gradle.properties` or env vars (never
hardcode secrets), and attach it to the `release` buildType. This repo ships
without a release signing config on purpose, so a clean checkout builds with zero
secrets.

<a name="troubleshooting"></a>
## Troubleshooting

| Symptom | Fix |
|---|---|
| `adb devices` shows `unauthorized` | Re-accept the "Allow USB debugging?" dialog on the phone; `adb kill-server && adb start-server` and replug. |
| Device shows `offline` | Bad/charge-only cable, or the phone locked during handshake — unlock and replug. |
| "Multiple devices online" error from a script | `scripts/devices.sh`, then `export ANDROID_SERIAL=<serial>`. |
| Wireless `connect` fails | Phone and host must be on the same Wi-Fi; re-pair (the connect port rotates when Wireless debugging is toggled). |
| `dump-a11y.sh` prints nothing | App not running, or its Accessibility service isn't enabled — `scripts/enable-service.sh`. |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | A build signed by a different key is installed — `adb -s "$ANDROID_SERIAL" uninstall com.assist`, then redeploy. |
| Screenshot is empty/black | Unlock and wake the screen; some secure screens (e.g. password fields) block capture. |

See also: [`README.md`](README.md) (build loop overview) and
[`.claude/phases/phase-11-device-deploy.md`](.claude/phases/phase-11-device-deploy.md)
(the work order).
