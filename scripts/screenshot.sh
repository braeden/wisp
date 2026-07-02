#!/usr/bin/env bash
# Capture a screenshot from the target device and save it to the host for
# inspection. Uses `adb exec-out screencap -p` (streams the PNG straight to a
# file — no on-device temp file, no pull step).
#
# Output defaults to the gitignored captures/ dir; override with an explicit path.
#
# Usage:
#   scripts/screenshot.sh                       # captures/screen-<serial>-<ts>.png
#   scripts/screenshot.sh /path/to/out.png      # explicit destination
#   scripts/screenshot.sh --open                # capture then open it (macOS `open`)
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
serial="$(target_device)"

open_after=0
out=""
for arg in "$@"; do
  case "$arg" in
    --open) open_after=1 ;;
    *) out="$arg" ;;
  esac
done

if [[ -z "$out" ]]; then
  dir="$ASSIST_ROOT/captures"
  mkdir -p "$dir"
  ts="$(date +%Y%m%d-%H%M%S)"
  # Sanitize serial for filenames (wireless serials look like ip:port).
  safe="${serial//[:.]/_}"
  out="$dir/screen-${safe}-${ts}.png"
fi

echo ">> capturing screenshot from $serial"
"$ADB" -s "$serial" exec-out screencap -p > "$out"
if [[ -s "$out" ]]; then
  echo ">> saved $out ($(wc -c < "$out" | tr -d ' ') bytes)"
  [[ "$open_after" -eq 1 ]] && command -v open >/dev/null 2>&1 && open "$out"
else
  echo "ERROR: screenshot was empty — is the device unlocked and awake?" >&2
  rm -f "$out"
  exit 1
fi
