#!/usr/bin/env bash
# Build then install the debug APK to the target device.
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
require_gradle
serial="$(target_device)"
echo ">> building..."
"$GRADLEW" -p "$ASSIST_ROOT" "${APP_MODULE}:assembleDebug"
[[ -f "$DEBUG_APK" ]] || { echo "ERROR: APK not found at $DEBUG_APK" >&2; exit 1; }
echo ">> installing to $serial"
"$ADB" -s "$serial" install -r -g "$DEBUG_APK"
echo ">> done"
