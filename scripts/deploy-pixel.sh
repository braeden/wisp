#!/usr/bin/env bash
# One-command deploy to a physical device (or emulator): build + install + launch.
#
# This is the "release-ish" personal-sideload path from phase-11. It builds the
# debug APK (signed with the default debug keystore — good enough for a personal
# sideload target) and installs+launches it on the selected device. Pick a device
# with ANDROID_SERIAL when several are attached (see scripts/devices.sh).
#
# Usage:
#   scripts/deploy-pixel.sh                       # deploy to the one online device
#   ANDROID_SERIAL=<pixel-serial> scripts/deploy-pixel.sh
#   scripts/deploy-pixel.sh --a11y                # also open a11y settings after
#   scripts/deploy-pixel.sh -- <extra gradle args>
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
require_gradle

enable_a11y=0
gradle_args=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --a11y) enable_a11y=1; shift ;;
    --) shift; gradle_args+=("$@"); break ;;
    *) gradle_args+=("$1"); shift ;;
  esac
done

# Pin the target so installDebug/runApp pick the right device with several attached.
serial="$(target_device)"
export ANDROID_SERIAL="$serial"

model="$("$ADB" -s "$serial" shell getprop ro.product.model 2>/dev/null | tr -d '\r' || true)"
echo ">> deploying to $serial${model:+ ($model)}  (JAVA_HOME=$JAVA_HOME)"

# runApp = :app:installDebug then `am start` the launcher (see gradle/device.gradle.kts).
"$GRADLEW" -p "$ASSIST_ROOT" runApp "${gradle_args[@]}"
echo ">> installed + launched com.assist on $serial"

if [[ "$enable_a11y" -eq 1 ]]; then
  echo ">> enabling accessibility service ..."
  "$(dirname "${BASH_SOURCE[0]}")/enable-service.sh"
fi

cat <<EOF
>> next:
     scripts/logcat.sh                 # stream app logs on $serial
     scripts/enable-service.sh         # grant Accessibility (manual toggle on a Pixel)
   Grant on-device (one time): Accessibility, Display over other apps, Microphone,
   Notifications. Full first-run steps: DEVICE.md
EOF
