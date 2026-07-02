#!/usr/bin/env bash
# Capture diagnostics when a run misbehaves: a full bugreport zip by default, or
# just the fast bits (--anr / --logcat) when you don't need the whole thing.
# Output lands in the gitignored captures/ dir.
#
# Usage:
#   scripts/bugreport.sh                # full `adb bugreport` zip (slow, ~1-2 min)
#   scripts/bugreport.sh --anr          # pull /data/anr traces + recent crash log only
#   scripts/bugreport.sh --logcat       # dump the current logcat buffer to a file only
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
serial="$(target_device)"

dir="$ASSIST_ROOT/captures"
mkdir -p "$dir"
ts="$(date +%Y%m%d-%H%M%S)"
safe="${serial//[:.]/_}"
mode="${1:-full}"

case "$mode" in
  --anr)
    out="$dir/anr-${safe}-${ts}.txt"
    echo ">> pulling ANR traces from $serial (needs a recent ANR)"
    # /data/anr is usually readable via `adb shell cat` even without root on debug builds.
    "$ADB" -s "$serial" shell "ls /data/anr/ 2>/dev/null" | tr -d '\r' | while read -r f; do
      [[ -n "$f" ]] || continue
      echo "==== /data/anr/$f ====" >> "$out"
      "$ADB" -s "$serial" shell "cat /data/anr/$f" 2>/dev/null >> "$out" || true
    done
    if [[ -s "$out" ]]; then echo ">> saved $out"; else echo ">> no ANR traces found"; rm -f "$out"; fi
    ;;

  --logcat)
    out="$dir/logcat-${safe}-${ts}.txt"
    echo ">> dumping logcat buffer from $serial"
    "$ADB" -s "$serial" logcat -d > "$out"
    echo ">> saved $out ($(wc -l < "$out" | tr -d ' ') lines)"
    ;;

  full|"")
    out="$dir/bugreport-${safe}-${ts}"
    echo ">> capturing full bugreport from $serial (this can take a minute)..."
    # Modern adb writes a .zip; give it the basename and let adb append the extension.
    "$ADB" -s "$serial" bugreport "$out"
    echo ">> saved ${out}.zip (unzip and read bugreport-*.txt; grep for 'AssistA11yService', 'AndroidRuntime', 'ANR in com.assist')"
    ;;

  *) echo "ERROR: unknown mode '$mode' (use --anr, --logcat, or no arg for full)" >&2; exit 1 ;;
esac
