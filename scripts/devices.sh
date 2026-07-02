#!/usr/bin/env bash
# List attached devices/emulators and show how to target one.
# With several attached, copy one of the printed `export ANDROID_SERIAL=…` lines
# (or set it inline) before running any other scripts/*.sh.
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
echo ">> attached devices (serial / state / model):"
"$ADB" devices -l | awk 'NR>1 && NF {print}'
echo
echo ">> select one for subsequent scripts:"
# Emit a ready-to-copy export line per online device, annotated with its model.
"$ADB" devices | awk 'NR>1 && $2=="device" {print $1}' | while read -r serial; do
  [[ -n "$serial" ]] || continue
  model="$("$ADB" -s "$serial" shell getprop ro.product.model 2>/dev/null | tr -d '\r' || true)"
  printf '   export ANDROID_SERIAL=%s   # %s\n' "$serial" "${model:-unknown}"
done
echo
echo "Then run e.g.:  scripts/deploy-pixel.sh   |   scripts/logcat.sh"
