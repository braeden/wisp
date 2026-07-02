#!/usr/bin/env bash
# List attached devices/emulators and show how to target one.
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
echo ">> attached devices (serial / state / model):"
"$ADB" devices -l | awk 'NR>1 && NF {print}'
echo
echo "Target a specific one by exporting ANDROID_SERIAL, e.g.:"
echo "  export ANDROID_SERIAL=<serial>   # then run any scripts/*.sh"
