#!/usr/bin/env bash
# Boot the Pixel 7 Pro API 35 emulator and wait for it to be ready.
# Usage: scripts/emulator.sh [--headless]  (extra args passed to the emulator)
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"

[[ -x "$EMULATOR" ]] || { echo "ERROR: emulator not found at $EMULATOR" >&2; exit 1; }
if ! "$EMULATOR" -list-avds | grep -qx "$AVD_NAME"; then
  echo "ERROR: AVD '$AVD_NAME' not found. Create it (see phase-01)." >&2
  "$EMULATOR" -list-avds >&2 || true
  exit 1
fi

extra=()
if [[ "${1:-}" == "--headless" ]]; then
  extra=(-no-window -no-audio -no-boot-anim); shift
fi

echo ">> booting $AVD_NAME ..."
"$EMULATOR" -avd "$AVD_NAME" -netdelay none -netspeed full "${extra[@]}" "$@" &
emu_pid=$!
echo ">> emulator pid $emu_pid; waiting for boot..."
"$ADB" wait-for-device
# Poll sys.boot_completed.
until [[ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; do
  sleep 2
done
"$ADB" shell input keyevent 82 >/dev/null 2>&1 || true   # dismiss keyguard
echo ">> emulator ready:"
"$ADB" devices | grep emulator
