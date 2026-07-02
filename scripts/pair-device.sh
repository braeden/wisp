#!/usr/bin/env bash
# Pair / connect a physical device over Wi-Fi (adb wireless debugging), or flip a
# USB-attached device into TCP/IP mode. No IPs/serials are baked in — you pass the
# address shown on the phone.
#
# Wireless debugging (Android 11+): Settings > System > Developer options >
# Wireless debugging. Two ports are involved:
#   - a one-time *pairing* port + 6-digit code (under "Pair device with pairing code")
#   - the persistent *connect* port shown on the main Wireless debugging screen
#
# Usage:
#   scripts/pair-device.sh pair    <ip:pairPort> [code]   # one-time pairing; prompts for code if omitted
#   scripts/pair-device.sh connect <ip:connectPort>       # connect to an already-paired phone
#   scripts/pair-device.sh usb     [port]                 # flip the USB device to tcpip (default 5555) + connect
#   scripts/pair-device.sh status                         # show adb devices -l
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"

usage() { sed -n '2,20p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit "${1:-0}"; }

cmd="${1:-}"; shift || true
case "$cmd" in
  pair)
    addr="${1:-}"; code="${2:-}"
    [[ -n "$addr" ]] || { echo "ERROR: need <ip:pairPort> (see 'Pair device with pairing code' on the phone)" >&2; usage 1; }
    echo ">> pairing with $addr"
    if [[ -n "$code" ]]; then
      "$ADB" pair "$addr" "$code"
    else
      # adb prompts for the 6-digit code interactively.
      "$ADB" pair "$addr"
    fi
    echo ">> paired. Now: scripts/pair-device.sh connect <ip:connectPort>"
    echo "   (connectPort is the port on the main Wireless debugging screen — NOT the pairing port)"
    ;;

  connect)
    addr="${1:-}"
    [[ -n "$addr" ]] || { echo "ERROR: need <ip:connectPort> from the Wireless debugging screen" >&2; usage 1; }
    echo ">> connecting to $addr"
    "$ADB" connect "$addr"
    echo ">> devices:"; "$ADB" devices -l | awk 'NR>1 && NF {print}'
    echo ">> target it with: export ANDROID_SERIAL=$addr"
    ;;

  usb)
    port="${1:-5555}"
    # Requires exactly one USB device; reuse env.sh's target resolution.
    serial="$(target_device)"
    ip="$("$ADB" -s "$serial" shell ip route 2>/dev/null | awk '/wlan0/ {print $9; exit}' | tr -d '\r')"
    echo ">> $serial: switching to tcpip on port $port"
    "$ADB" -s "$serial" tcpip "$port"
    sleep 1
    if [[ -n "$ip" ]]; then
      echo ">> connecting to $ip:$port (Wi-Fi)"
      "$ADB" connect "$ip:$port"
      echo ">> you can now unplug USB. Target it with: export ANDROID_SERIAL=$ip:$port"
    else
      echo ">> could not auto-detect the phone's Wi-Fi IP." >&2
      echo "   Find it under Settings > About phone > IP address, then:" >&2
      echo "     $ADB connect <ip>:$port" >&2
    fi
    ;;

  status|"")
    "$ADB" devices -l | awk 'NR>1 && NF {print}'
    ;;

  -h|--help|help) usage 0 ;;
  *) echo "ERROR: unknown command '$cmd'" >&2; usage 1 ;;
esac
