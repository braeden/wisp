#!/usr/bin/env bash
# Install + launch the app. Thin wrapper: resolves JDK 17 + SDK, targets a device
# (via ANDROID_SERIAL if set), then delegates to Gradle's runApp task.
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
require_gradle
export ANDROID_SERIAL="$(target_device)"
echo ">> runApp (install + launch) on $ANDROID_SERIAL (JAVA_HOME=$JAVA_HOME)"
"$GRADLEW" -p "$ASSIST_ROOT" runApp "$@"
