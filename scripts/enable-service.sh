#!/usr/bin/env bash
# Enable the Assist accessibility service.
# On an emulator (or rooted device) it sets the secure setting directly.
# On a real device it opens the Accessibility settings screen for a manual toggle.
# The service component is created in phase-03.
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
serial="$(target_device)"
SERVICE="$APP_ID/.service.AssistAccessibilityService"

echo ">> target service: $SERVICE on $serial"
if "$ADB" -s "$serial" shell settings put secure enabled_accessibility_services "$SERVICE" >/dev/null 2>&1; then
  "$ADB" -s "$serial" shell settings put secure accessibility_enabled 1 >/dev/null 2>&1 || true
  current="$("$ADB" -s "$serial" shell settings get secure enabled_accessibility_services | tr -d '\r')"
  echo ">> enabled_accessibility_services = $current"
  echo ">> (if the service isn't installed yet, this takes effect after phase-03 + install)"
else
  echo ">> could not set secure setting (non-rooted device). Opening Accessibility settings..."
  "$ADB" -s "$serial" shell am start -a android.settings.ACCESSIBILITY_SETTINGS >/dev/null 2>&1 || true
  echo ">> Toggle 'Assist' on manually under Installed apps / Downloaded services."
fi
