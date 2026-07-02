#!/usr/bin/env bash
# Shared environment for the Assist build/debug loop.
# Source this from other scripts: `source "$(dirname "$0")/env.sh"`
#
# Resolves ANDROID_HOME, a JDK 17 JAVA_HOME, key paths, and a target device.
# Override any of these by exporting them before invoking a script.

set -euo pipefail

# --- Repo root (scripts/ lives directly under it) -------------------------
# Works whether run via bash (shebang) or sourced. $0 is the fallback for shells
# that don't populate BASH_SOURCE (e.g. sourcing from an interactive zsh).
_assist_self="${BASH_SOURCE[0]:-$0}"
ASSIST_ROOT="$(cd "$(dirname "$_assist_self")/.." && pwd)"

# --- Android SDK ----------------------------------------------------------
: "${ANDROID_HOME:=${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
export ANDROID_HOME ANDROID_SDK_ROOT="$ANDROID_HOME"
ADB="$ANDROID_HOME/platform-tools/adb"
EMULATOR="$ANDROID_HOME/emulator/emulator"
SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
AVDMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager"

# --- JDK 17 ---------------------------------------------------------------
# Precedence: caller's JAVA_HOME (if it is 17) -> /usr/libexec/java_home -v 17
# -> Temurin in ~/Library/Java -> any 17.x under Homebrew Cellar.
resolve_java_home() {
  if [[ -n "${ASSIST_JAVA_HOME:-}" && -x "$ASSIST_JAVA_HOME/bin/java" ]]; then
    echo "$ASSIST_JAVA_HOME"; return
  fi
  if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]] \
     && "$JAVA_HOME/bin/java" -version 2>&1 | grep -q '"17'; then
    echo "$JAVA_HOME"; return
  fi
  if command -v /usr/libexec/java_home >/dev/null 2>&1; then
    local jh; jh="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
    [[ -n "$jh" ]] && { echo "$jh"; return; }
  fi
  local cand
  for cand in \
    "$HOME/Library/Java/JavaVirtualMachines"/*/Contents/Home \
    /opt/homebrew/Cellar/openjdk/17*/libexec/openjdk.jdk/Contents/Home; do
    [[ -x "$cand/bin/java" ]] && "$cand/bin/java" -version 2>&1 | grep -q '"17' \
      && { echo "$cand"; return; }
  done
  return 1
}

if ! JAVA_HOME="$(resolve_java_home)"; then
  echo "ERROR: No JDK 17 found. Install one (Temurin 17 into ~/Library/Java/JavaVirtualMachines)" >&2
  echo "       or export ASSIST_JAVA_HOME=/path/to/jdk17." >&2
  exit 1
fi
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

# --- App coordinates (kept in sync with CONVENTIONS.md) -------------------
APP_ID="com.assist"
APP_MODULE=":app"
LAUNCHER_ACTIVITY="com.assist/.ui.MainActivity"   # created in phase-02
DEBUG_APK="$ASSIST_ROOT/app/build/outputs/apk/debug/app-debug.apk"
AVD_NAME="pixel7pro_api35"
GRADLEW="$ASSIST_ROOT/gradlew"

# --- Helpers --------------------------------------------------------------
require_gradle() {
  [[ -x "$GRADLEW" ]] || { echo "ERROR: $GRADLEW not found. Run phase-02 (app skeleton) first." >&2; exit 1; }
}

# Echo the target device serial (respects ANDROID_SERIAL; else the only online
# device; else errors and lists candidates).
target_device() {
  if [[ -n "${ANDROID_SERIAL:-}" ]]; then echo "$ANDROID_SERIAL"; return; fi
  local devices
  devices="$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1}')"
  local n; n="$(printf '%s\n' "$devices" | grep -c . || true)"
  if [[ "$n" -eq 1 ]]; then echo "$devices"; return; fi
  if [[ "$n" -eq 0 ]]; then
    echo "ERROR: No online device/emulator. Start the emulator (scripts/emulator.sh) or plug in the Pixel." >&2
    exit 1
  fi
  echo "ERROR: Multiple devices online. Set ANDROID_SERIAL to one of:" >&2
  printf '%s\n' "$devices" >&2
  exit 1
}

adb_t() { "$ADB" -s "$(target_device)" "$@"; }
