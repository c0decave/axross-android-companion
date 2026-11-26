#!/usr/bin/env bash
# The emulator's smoke test (CI; any emulator `adb` sees will do):
#   scripts/smoke_test.sh path/to/app-debug.apk [output-dir]
#
# What axross does, the same way: install the APK, grant the permissions
# with `pm grant`, start the service with `am start-foreground-service
# --es token <a fresh token>`, `adb forward` a TCP port to
# localabstract:axross-companion - then the reference client
# (scripts/ci/companion_client.py) on this side: the handshake with that
# token (hello with the companion's proof, auth), status, SIMs, contacts and
# threads (empty is fine), the refusals (too many recipients, a bad number,
# before hello, a version-1 hello, an unknown token, a wrong proof, a
# request before auth, a broken frame, delete without being the default SMS
# app), and an SMS to the emulator's own number, asked for twice with one
# client_ref: one send, the sms.sent event must arrive, sms.status by
# client_ref; the SMS coming back (message.received), it being found in a
# thread and by search, are checked and reported; an export, and one paced
# by backup.ack (window 1) that answers a ping while it waits. Then eight
# more tokens push the first one out (unknown_token) and the newest works.
#
# Then the restart (revision 1): the service is stopped and its process
# killed, the service started again with a new token - the send store must
# be the same (hello's sends.store), sms.status must still know the send,
# and the same client_ref again must be a duplicate (not sent again). The
# fallback start (`am start ... ShellStartActivity --es token`) must hand
# its token to the service; MainActivity, the launcher's, must not take one.
#
# Afterwards: the app's process (the second one) is still alive, its log
# has no crash, and its own log lines carry neither the text sent, nor the
# number, nor a token (privacy).
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
# (the first run's log is kept before the restart clears logcat)
all_logcat() { cat "$OUT/logcat-run1.txt" 2>/dev/null; adb logcat -d 2>/dev/null; }
all_app_log() { cat "$OUT/app-log-run1.txt" 2>/dev/null; app_log; }
app_pid() { adb shell pidof "$PACKAGE" 2>/dev/null | tr -d '\r' | awk '{print $1}'; }

# An annotation (readable through the API when logs and artifacts are not).
annotate() { # level title file-or-text
    local msg
    if [ -f "$3" ]; then msg=$(tail -c 3900 "$3"); else msg=$3; fi
    msg=${msg//'%'/'%25'}
    msg=${msg//$'\r'/}
    msg=${msg//$'\n'/'%0A'}
    echo "::$1 title=$2::$msg"
}

finish() {
    adb logcat -d > "$OUT/logcat.txt" 2>/dev/null
    all_app_log > "$OUT/app-log.txt"
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

new_token() { python3 -I -c 'import secrets; print(secrets.token_hex(32))'; }
# The service is given a token on every start (docs/PROTOCOL.md, section 4).
start_service() { # token
    local started
    started=$(adb shell am start-foreground-service -n "$PACKAGE/$CLASSES.CompanionService" --es token "$1" 2>&1 | tr -d '\r')
    echo "$started" | grep -v -e "$1"
    if echo "$started" | grep -qi "error"; then
        echo "::warning title=smoke test::am start-foreground-service was refused; starting through ShellStartActivity"
        shell_start "$1"
    fi
}
# The fallback: the activity only the shell may start hands the token on.
shell_start() { # token
    adb shell am start -W -n "$PACKAGE/$CLASSES.ShellStartActivity" --es token "$1" 2>&1 | tr -d '\r' | grep -v -e "$1"
}
wait_listening() { # seconds
    local waited=0
    until app_log | grep -q "listening on localabstract"; do
        if [ "$waited" -ge "$1" ]; then
            return 1
        fi
        sleep 1; waited=$((waited + 1))
    done
}
ping_with() { # token -> the reference client's output
    python3 -I "$HERE/ci/companion_client.py" --port "$PORT" --token "$1" call ping 2>&1
}
TOKEN=$(new_token)
TOKENS="$TOKEN"

adb logcat -c
say "starting the service (am start-foreground-service --es token)"
start_service "$TOKEN"

if ! wait_listening 30; then
    say "the service does not listen within 30 s"; finish; exit 1
fi
PID=$(app_pid)
say "listening (pid $PID)"

adb forward "tcp:$PORT" localabstract:axross-companion || { say "adb forward failed"; finish; exit 1; }
# (--inject: an incoming SMS from the emulator's console while the client is
# subscribed - the message.received check that does not depend on the
# modem looping our own send back)
python3 -I "$HERE/ci/companion_client.py" --port "$PORT" --token "$TOKEN" smoke --sms-to "$NUMBER" --out "$OUT" \
    --inject "adb emu sms send 15550001234 {text}"
result=$?

# eight more tokens: the first is pushed out, the newest works
for i in 1 2 3 4 5 6 7 8; do
    LAST=$(new_token)
    TOKENS="$TOKENS $LAST"
    start_service "$LAST" > /dev/null
done
sleep 2
GOT=$(ping_with "$TOKEN")
if echo "$GOT" | grep -q '"unknown_token"'; then
    say "ok   the first token, after eight more: unknown_token"
else
    say "FAIL the first token, after eight more: expected unknown_token, got: $(echo "$GOT" | head -c 300)"
    result=1
fi
GOT=$(ping_with "$LAST")
if echo "$GOT" | grep -q '"time"'; then
    say "ok   the newest token works"
else
    say "FAIL the newest token: $(echo "$GOT" | head -c 300)"
    result=1
fi

if [ "$(app_pid)" != "$PID" ]; then
    say "FAIL the app's process died or was started again during the test"
    result=1
fi

# The restart: the service stops, its process goes, the service starts again.
say "restart: stopping the service and its process"
adb shell am stopservice -n "$PACKAGE/$CLASSES.CompanionService" >/dev/null 2>&1 || true
sleep 3
adb shell am kill "$PACKAGE" >/dev/null 2>&1 || true
sleep 2
if [ -n "$(app_pid)" ]; then
    # (still there: a stop that leaves no process at all)
    adb shell am force-stop "$PACKAGE" >/dev/null 2>&1 || true
    sleep 2
fi
if [ -n "$(app_pid)" ]; then
    say "FAIL the app's process does not end (pid $(app_pid))"
    result=1
fi
adb logcat -d > "$OUT/logcat-run1.txt" 2>/dev/null
app_log > "$OUT/app-log-run1.txt"
adb logcat -c
TOKEN2=$(new_token)
TOKENS="$TOKENS $TOKEN2"
start_service "$TOKEN2"
if ! wait_listening 30; then
    say "FAIL the service does not listen again within 30 s"; finish; exit 1
fi
PID2=$(app_pid)
say "listening again (pid $PID2, was $PID)"
if [ "$PID2" = "$PID" ]; then
    say "FAIL the restart kept the process (pid $PID)"
    result=1
fi
python3 -I "$HERE/ci/companion_client.py" --port "$PORT" --token "$TOKEN2" smoke-restart --out "$OUT" || result=1

# The fallback start, and the launcher's screen that takes no token.
TOKEN3=$(new_token)
TOKEN4=$(new_token)
TOKENS="$TOKENS $TOKEN3 $TOKEN4"
shell_start "$TOKEN3" > /dev/null
sleep 2
GOT=$(ping_with "$TOKEN3")
if echo "$GOT" | grep -q '"time"'; then
    say "ok   ShellStartActivity --es token: the token works"
else
    say "FAIL ShellStartActivity --es token: $(echo "$GOT" | head -c 300)"
    result=1
fi
adb shell am start -W -n "$PACKAGE/$CLASSES.MainActivity" --ez listen true --es token "$TOKEN4" 2>&1 | tr -d '\r' | grep -v -e "$TOKEN4"
sleep 2
GOT=$(ping_with "$TOKEN4")
if echo "$GOT" | grep -q '"unknown_token"'; then
    say "ok   MainActivity takes no token (unknown_token)"
else
    say "FAIL MainActivity took a token: $(echo "$GOT" | head -c 300)"
    result=1
fi
adb shell input keyevent KEYCODE_HOME >/dev/null 2>&1 || true

if [ "$(app_pid)" != "$PID2" ]; then
    say "FAIL the app's process died or was started again after the restart"
    result=1
fi
if all_logcat | grep -A5 "FATAL EXCEPTION" | grep -q "$PACKAGE"; then
    say "FAIL the app crashed"
    result=1
fi
# privacy: no message text and no number in the app's own lines
if all_app_log | grep -q -e "axross companion smoke" -e "$NUMBER" -e "${NUMBER#1}" -e 15550001234; then
    say "FAIL the app's log carries the text or the number"
    result=1
fi
for t in $TOKENS; do
    if all_app_log | grep -q -e "$t" -e "${t:0:16}"; then
        say "FAIL the app's log carries a token"
        result=1
    fi
done

# the idle stop is five minutes: not waited for; a clean stop is
adb shell am stopservice -n "$PACKAGE/$CLASSES.CompanionService" >/dev/null 2>&1 || true
finish
[ "$result" -eq 0 ] && say "PASS" || say "FAIL"
exit "$result"
