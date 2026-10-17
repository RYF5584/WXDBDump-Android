#!/bin/bash
# Log capture helper.
#
#   ./tools/logcat.sh xposed     # one Xposed-mode round; start it before WeChat launches
#   ./tools/logcat.sh root       # one root-mode round
#   ./tools/logcat.sh all        # no mode filter, everything goes to all-<ts>.log
#
# Keeps only this project's logs, which share one tag across both processes, plus the
# AndroidRuntime crash summary, so system noise does not drown them out.
#
# Stop with Ctrl-C; the file path is printed on exit.

set -o pipefail

MODE="${1:-xposed}"
case "$MODE" in
  xposed|root|all) ;;
  *) echo "usage: $0 [xposed|root|all]" >&2; exit 2 ;;
esac

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT_DIR="$ROOT/logs"
mkdir -p "$OUT_DIR"
TS="$(date +%Y%m%d-%H%M%S)"
OUT="$OUT_DIR/${MODE}-${TS}.log"

if ! command -v adb >/dev/null 2>&1; then
  echo "adb not found; add platform-tools to PATH" >&2
  exit 1
fi

DEVICES="$(adb devices | awk 'NR>1 && $2=="device" {print $1}')"
if [ -z "$DEVICES" ]; then
  echo "no device attached; connect the phone and allow USB debugging" >&2
  exit 1
fi

echo "device: $(echo "$DEVICES" | tr '\n' ' ')"
echo "clearing the logcat buffer"
adb logcat -c || true

echo "capturing into $OUT"
echo "(Ctrl-C to stop)"
if [ "$MODE" = "xposed" ]; then
  echo
  echo "Note: module logs are emitted while WeChat starts, so after this command:"
  echo "   1) force-stop WeChat  2) launch it and wait for the main screen  3) export from this app"
  echo
elif [ "$MODE" = "root" ]; then
  echo
  echo "Note, after this command:"
  echo "   1) fully exit this app  2) reopen, pick root mode, grant su, then export"
  echo
fi

cleanup() {
  echo
  echo "capture stopped."
  local total=0
  if [ -s "$OUT" ]; then
    total="$(wc -l < "$OUT" | tr -d ' ')"
  fi
  echo "log file: $OUT"
  echo "lines: $total"
  if [ "$total" -gt 0 ]; then
    # Count with awk: grep -c exits 1 on no match, which a fallback would turn into two lines
    count() { awk -v p="$1" 'index($0,p){n++} END{printf "%d", n+0}' "$OUT"; }
    echo
    echo "--- self-check (counts; 0 means the marker never appeared) ---"
    printf '  %-24s %s\n' "MODULE INJECTED"   "$(count 'MODULE INJECTED')"
    printf '  %-24s %s\n' "ENGINE START"      "$(count 'ENGINE START')"
    printf '  %-24s %s\n' "loopback listening"  "$(count 'loopback server listening')"
    printf '  %-24s %s\n' "hook anchors"       "$(count 'attempt#')"
    printf '  %-24s %s\n' "handshake"          "$(count 'handshake complete')"
    printf '  %-24s %s\n' "KEY CAPTURED"      "$(count 'KEY CAPTURED')"
    printf '  %-24s %s\n' "verify result"      "$(count 'verify result')"
    printf '  %-24s %s\n' "EXPORT START"      "$(count 'EXPORT START')"
    printf '  %-24s %s\n' "EXPORT FINISHED"   "$(count 'EXPORT FINISHED')"
    printf '  %-24s %s\n' "uncaught/crash"     "$(count 'UNCAUGHT EXCEPTION')"
    echo
    echo "Send the log file path above when reporting a problem."
  else
    echo "The log file is empty: no project logs were captured."
    echo "Likely causes: no device, APK not installed, or the app never started."
  fi
  exit 0
}
trap cleanup INT TERM

# The tag is fixed; AndroidRuntime entries reveal whether WeChat crashed
adb logcat -v threadtime -s WxDbExport:* AndroidRuntime:E '*:S' | tee "$OUT"
