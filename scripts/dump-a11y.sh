#!/usr/bin/env bash
# Trigger the phase-03 accessibility screen dump on the target device and print
# the result. Fires the com.assist.DEBUG_DUMP_SCREEN broadcast that
# AssistAccessibilityService listens for, then tails the "SCREEN DUMP" it logs.
#
# Requires: the app installed AND its Accessibility service enabled
# (scripts/enable-service.sh). Otherwise no receiver is registered and nothing logs.
#
# Usage:
#   scripts/dump-a11y.sh                     # dump the current screen outline
#   scripts/dump-a11y.sh --screenshot        # also log screenshot size
#   scripts/dump-a11y.sh --open "Settings"   # openApp(label) first, then dump
#   scripts/dump-a11y.sh --tap 12            # tap element id 12, then dump
#   scripts/dump-a11y.sh --swipe up          # swipe (up/down/left/right), then dump
#   scripts/dump-a11y.sh --key BACK          # pressKey (BACK/HOME/RECENTS/...), then dump
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
serial="$(target_device)"

extras=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --screenshot) extras+=(--ez screenshot true); shift ;;
    --open)  extras+=(--es open "$2");  shift 2 ;;
    --tap)   extras+=(--ei tap "$2");   shift 2 ;;
    --swipe) extras+=(--es swipe "$2"); shift 2 ;;
    --key)   extras+=(--es key "$2");   shift 2 ;;
    *) echo "ERROR: unknown arg '$1'" >&2; exit 1 ;;
  esac
done

echo ">> clearing logcat + firing DEBUG_DUMP_SCREEN on $serial"
"$ADB" -s "$serial" logcat -c || true
"$ADB" -s "$serial" shell am broadcast \
  -a com.assist.DEBUG_DUMP_SCREEN \
  "${extras[@]}" >/dev/null

# The service handles the broadcast on a coroutine; give it a moment to log.
sleep 2
echo ">> AssistA11yService output:"
# -d = dump-and-exit (non-streaming); filter to the service tag.
"$ADB" -s "$serial" logcat -d -s AssistA11yService:I || true
echo ">> (empty? confirm the app is running and its Accessibility service is enabled: scripts/enable-service.sh)"
