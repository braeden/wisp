#!/usr/bin/env bash
# Build the debug APK.
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
require_gradle
echo ">> assembleDebug (JAVA_HOME=$JAVA_HOME)"
"$GRADLEW" -p "$ASSIST_ROOT" "${APP_MODULE}:assembleDebug" "$@"
echo ">> APK: $DEBUG_APK"
