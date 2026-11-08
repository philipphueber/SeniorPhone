#!/usr/bin/env bash
# SeniorPhone smoke test, on an emulator (it simulates calls with "adb emu gsm"):
#   - it becomes the default phone app and shows the contact list
#   - a call out shows our call screen, and Hang up ends it
#   - a call in, with the screen off, shows Answer; Answer connects it;
#     the caller hanging up shows "Call ended"
# Usage: scripts/smoke.sh [serial]
set -u
PKG=app.phueber.seniorphone
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-/home/philipp/.local/opt/jdk-17.0.20.1+1}"
export PATH="$JAVA_HOME/bin:$PATH"
SERIAL="${1:-$(adb devices | awk '/emulator-[0-9]+\tdevice/{print $1; exit}')}"
[ -n "$SERIAL" ] || { echo "FAIL no emulator"; exit 2; }
A() { adb -s "$SERIAL" "$@"; }
SH() { A shell "$@" | tr -d '\r'; }
XML=$(mktemp)
NUMBER=5551234
PREV_DIALER=$(SH cmd role get-role-holders android.app.role.DIALER | head -1)
cleanup() {
  A emu gsm cancel $NUMBER >/dev/null 2>&1
  if [ -n "$PREV_DIALER" ] && [ "$PREV_DIALER" != "$PKG" ]; then
    SH cmd role add-role-holder android.app.role.DIALER "$PREV_DIALER" >/dev/null 2>&1
  fi
  rm -f "$XML"
}
pass() { echo "PASS $*"; }
fail() { echo "FAIL $*"; cleanup; exit 1; }

dump_ui() { # retry: uiautomator dump flakes with "could not get idle state"
  for _ in 1 2 3 4 5 6; do
    SH uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 \
      && A pull /sdcard/ui.xml "$XML" >/dev/null 2>&1 \
      && grep -q '<hierarchy' "$XML" && return 0
    sleep 1
  done
  return 1
}
has_text() { dump_ui && grep -qiE "(text|content-desc)=\"[^\"]*$1" "$XML"; }
wait_text() { for _ in $(seq 1 "${2:-10}"); do has_text "$1" && return 0; sleep 1; done; return 1; }
tap_text() { # tap the first node whose text or content-desc starts with $1
  dump_ui || return 1
  local xy
  xy=$(python3 -I - "$XML" "$1" <<'PY'
import sys, re, xml.etree.ElementTree as ET
f, val = sys.argv[1], sys.argv[2].lower()
for n in ET.parse(f).getroot().iter('node'):
    for k in ('text', 'content-desc'):
        if n.get(k, '').lower().startswith(val):
            a, b, c, d = map(int, re.findall(r'\d+', n.get('bounds')))
            print((a + c) // 2, (b + d) // 2); sys.exit(0)
sys.exit(1)
PY
) || return 1
  SH input tap $xy >/dev/null
}
no_call() { ! SH dumpsys telecom | grep -qE 'state: (ACTIVE|DIALING|RINGING|CONNECTING)'; }

echo "== $SERIAL (API $(SH getprop ro.build.version.sdk)) =="
(cd "$ROOT" && ./gradlew assembleDebug -q) && pass "assembleDebug" || fail "assembleDebug"
APK=$(ls "$ROOT"/app/build/outputs/apk/debug/SeniorPhone-*-debug.apk | head -1)
A install -r "$APK" 2>&1 | grep -q Success && pass "install" || fail "install"
SH settings put global window_animation_scale 0; SH settings put global transition_animation_scale 0
SH settings put global animator_duration_scale 0
SH input keyevent KEYCODE_WAKEUP >/dev/null; SH wm dismiss-keyguard >/dev/null 2>&1

# 1. Default phone app, contact list
SH cmd role add-role-holder android.app.role.DIALER "$PKG" >/dev/null 2>&1
SH cmd role get-role-holders android.app.role.DIALER | grep -qx "$PKG" \
  && pass "is the default phone app" || fail "did not get the dialer role"
for p in READ_CONTACTS WRITE_CONTACTS CALL_PHONE READ_PHONE_STATE POST_NOTIFICATIONS; do
  SH pm grant "$PKG" android.permission.$p >/dev/null 2>&1; done
SH am start -n "$PKG/.ContactsActivity" >/dev/null
wait_text 'Number pad' && pass "contact list shown" || fail "contact list not shown"

# 2. Call out
SH am start -a android.intent.action.CALL -d tel:$NUMBER >/dev/null
wait_text 'Hang up' && pass "call out: our call screen" || fail "no call screen for an outgoing call"
wait_text 'Connected' 15 && pass "call out connected" || fail "outgoing call never connected"
tap_text 'Hang up' || fail "Hang up not found"
for _ in $(seq 1 8); do no_call && break; sleep 1; done
no_call && pass "Hang up ended the call" || fail "call still on after Hang up"
sleep 4

# 3. Call in, screen off
SH input keyevent KEYCODE_SLEEP >/dev/null; sleep 2
A emu gsm call $NUMBER >/dev/null
wait_text 'is calling' 15 && pass "call in: shown with the screen off" || fail "incoming call screen not shown"
tap_text 'Answer' || fail "Answer not found"
wait_text 'Connected' 10 && pass "Answer connected the call" || fail "Answer did not connect"
A emu gsm cancel $NUMBER >/dev/null
wait_text 'Call ended' 6 && pass "caller hung up: 'Call ended'" || fail "no 'Call ended' after the caller hung up"
for _ in $(seq 1 8); do SH dumpsys activity activities | grep -q 'topResumedActivity.*CallActivity' || break; sleep 1; done
SH dumpsys activity activities | grep -q 'topResumedActivity.*CallActivity' \
  && fail "call screen still open after the call ended" || pass "call screen closed by itself"

cleanup
echo "ALL PASS"
