#!/usr/bin/env bash
# The emulator's smoke test (CI; any emulator `adb` sees will do):
#   scripts/smoke_test.sh path/to/app-debug.apk [output-dir]
#
# What axross does, the same way: install the APK, grant the permissions
# with `pm grant`, start the service with `am start-foreground-service`,
# `adb forward` a TCP port to localabstract:axross-companion - then the
# reference client (scripts/ci/companion_client.py) on this side: hello,
# status, SIMs, contacts and threads (empty is fine), the refusals (too
# many recipients, a bad number, before hello, a broken frame, delete
# without being the default SMS app), and an SMS to the emulator's own
# number: the sms.sent event must arrive; the SMS coming back
# (message.received), it being found in a thread and by search, are
# checked and reported.
#
# Afterwards: the app's process is still alive, its log has no crash, and
# its own log lines carry neither the text sent nor the number (privacy).
#
# Leaves logcat.txt, app-log.txt and smoke-client.json in the output directory.
set -u

APK=${1:?the APK to test}
OUT=${2:-smoke-output}
PACKAGE=${COMPANION_PACKAGE:-io.github.c0decave.axross.companion.debug}
CLASSES=io.github.c0decave.axross.companion
PORT=${COMPANION_PORT:-38999}
HERE=$(cd "$(dirname "$0")" && pwd)

mkdir -p "$OUT"
say() { printf '%s\n' "smoke: $*"; }
app_log() { adb logcat -d -s axross-companion:V 2>/dev/null; }
app_pid() { adb shell pidof "$PACKAGE" 2>/dev/null | tr -d '\r' | awk '{print $1}'; }

# An annotation (readable through the API when logs and artifacts are not).
annotate() { # level title file-or-text
    local msg
    if [ -f "$3" ]; then msg=$(tail -c 30000 "$3"); else msg=$3; fi
    msg=${msg//'%'/'%25'}
    msg=${msg//$'\r'/}
    msg=${msg//$'\n'/'%0A'}
    echo "::$1 title=$2::$msg"
}

finish() {
    adb logcat -d > "$OUT/logcat.txt" 2>/dev/null
    app_log > "$OUT/app-log.txt"
    adb forward --remove "tcp:$PORT" >/dev/null 2>&1
    say "the app's own lines:"
    cat "$OUT/app-log.txt"
    # what the phone side did with the SMS (incoming handler, the SMS role)
    {
        echo "API $SDK"
        if [ "$SDK" -ge 29 ]; then
            echo "SMS role: $(adb shell cmd role get-role-holders android.app.role.SMS 2>&1 | tr -d '\r')"
        fi
        echo "default sms (settings): $(adb shell settings get secure sms_default_application 2>&1 | tr -d '\r')"
        echo "inbound SMS handler lines: $(grep -c -i 'InboundSmsHandler' "$OUT/logcat.txt")"
        grep -i -E 'InboundSmsHandler|SmsDispatcher|MmsSmsProvider|SmsProvider' "$OUT/logcat.txt" | grep -v -i 'pdu' | tail -15 | cut -c1-200
    } > "$OUT/diagnostics.txt" 2>&1
    annotate notice "app log (API $SDK)" "$OUT/app-log.txt"
    annotate notice "telephony (API $SDK)" "$OUT/diagnostics.txt"
    [ -f "$OUT/smoke-client.json" ] && annotate notice "smoke client (API $SDK)" "$OUT/smoke-client.json"
    return 0
}

adb wait-for-device
SDK=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
SERIAL=$(adb get-serialno | tr -d '\r')
# (an emulator's number: 1 555 521 and its console port, e.g. 15555215554)
NUMBER=${COMPANION_SMS_TO:-1555521${SERIAL#emulator-}}
say "Android API $SDK, device $SERIAL, own number $NUMBER"

say "installing $APK"
adb install -r "$APK" || { say "the APK could not be installed"; exit 1; }

PERMISSIONS="READ_SMS SEND_SMS RECEIVE_SMS READ_CONTACTS READ_PHONE_STATE"
[ "$SDK" -ge 33 ] && PERMISSIONS="$PERMISSIONS POST_NOTIFICATIONS"
for p in $PERMISSIONS; do
    if ! adb shell pm grant "$PACKAGE" "android.permission.$p"; then
        say "pm grant $p failed"; finish; exit 1
    fi
done
say "granted: $PERMISSIONS"

adb logcat -c
say "starting the service (am start-foreground-service)"
STARTED=$(adb shell am start-foreground-service -n "$PACKAGE/$CLASSES.CompanionService" 2>&1 | tr -d '\r')
echo "$STARTED"
if echo "$STARTED" | grep -qi "error"; then
    echo "::warning title=smoke test::am start-foreground-service was refused; starting through the activity (--ez listen true)"
    adb shell am start -W -n "$PACKAGE/$CLASSES.MainActivity" --ez listen true
fi

waited=0
until app_log | grep -q "listening on localabstract"; do
    if [ "$waited" -ge 30 ]; then
        say "the service does not listen within 30 s"; finish; exit 1
    fi
    sleep 1; waited=$((waited + 1))
done
PID=$(app_pid)
say "listening (pid $PID)"

adb forward "tcp:$PORT" localabstract:axross-companion || { say "adb forward failed"; finish; exit 1; }
python3 -I "$HERE/ci/companion_client.py" --port "$PORT" smoke --sms-to "$NUMBER" --out "$OUT"
result=$?

if [ "$(app_pid)" != "$PID" ]; then
    say "FAIL the app's process died or was started again during the test"
    result=1
fi
if adb logcat -d 2>/dev/null | grep -A5 "FATAL EXCEPTION" | grep -q "$PACKAGE"; then
    say "FAIL the app crashed"
    result=1
fi
# privacy: no message text and no number in the app's own lines
if app_log | grep -q -e "axross companion smoke" -e "$NUMBER" -e "${NUMBER#1}"; then
    say "FAIL the app's log carries the text or the number"
    result=1
fi

# the idle stop is five minutes: not waited for; a clean stop is
adb shell am stopservice -n "$PACKAGE/$CLASSES.CompanionService" >/dev/null 2>&1 || true
finish
[ "$result" -eq 0 ] && say "PASS" || say "FAIL"
exit "$result"
