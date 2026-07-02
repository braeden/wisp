#!/usr/bin/env bash
# Install and launch the app's main activity.
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
serial="$(target_device)"
bash "$(dirname "${BASH_SOURCE[0]}")/install.sh"
echo ">> launching $LAUNCHER_ACTIVITY on $serial"
"$ADB" -s "$serial" shell am start -n "$LAUNCHER_ACTIVITY"
