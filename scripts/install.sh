#!/usr/bin/env bash
# Build + install the debug APK. Thin wrapper: resolves JDK 17 + SDK, targets a
# device (via ANDROID_SERIAL if set), then delegates to Gradle's installDebug.
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
require_gradle
# Pin the target so installDebug picks the right device when several are attached.
export ANDROID_SERIAL="$(target_device)"
echo ">> installDebug to $ANDROID_SERIAL (JAVA_HOME=$JAVA_HOME)"
"$GRADLEW" -p "$ASSIST_ROOT" "${APP_MODULE}:installDebug" "$@"
echo ">> done"
