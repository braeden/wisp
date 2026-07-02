#!/usr/bin/env bash
# Tail logcat filtered to the app process (falls back to the Assist tag prefix).
# Usage: scripts/logcat.sh [extra logcat args]
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
serial="$(target_device)"
pid="$("$ADB" -s "$serial" shell pidof "$APP_ID" 2>/dev/null | tr -d '\r' || true)"
"$ADB" -s "$serial" logcat -c || true
if [[ -n "$pid" ]]; then
  echo ">> logcat for $APP_ID (pid $pid) on $serial"
  exec "$ADB" -s "$serial" logcat --pid="$pid" "$@"
else
  echo ">> $APP_ID not running; filtering by tag Assist* + AndroidRuntime on $serial"
  exec "$ADB" -s "$serial" logcat "$@" Assist:V AssistApp:V AndroidRuntime:E "*:S"
fi
