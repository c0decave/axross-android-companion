#!/usr/bin/env bash
# The release job's check of the signed APK (release.yml calls it):
#   bash scripts/ci/verify_release.sh VERSION
# Signed (apksigner), by the certificate $EXPECTED names when it is set
# (the repository variable COMPANION_CERT_SHA256), asking for no network
# permission; copied to release/axross-android-companion-VERSION.apk.
set -euo pipefail
# (what failed, as an annotation: logs may be out of reach)
trap 'echo "::error title=verify_release.sh::line $LINENO: $BASH_COMMAND (exit $?)"' ERR
VERSION=${1:?the version}
mkdir -p release
BUILT=android/app/build/outputs/apk/release/app-release.apk
test -f "$BUILT" || { echo "::error::no signed APK (app-release.apk) was built"; ls android/app/build/outputs/apk/release || true; exit 1; }
# (the newest apksigner that runs on this JDK: a newer build-tools' may want
# a newer Java than the build's 17)
APKSIGNER=""
for candidate in $(ls "$ANDROID_HOME"/build-tools/*/apksigner | sort -Vr); do
    if "$candidate" version > /dev/null 2>&1; then
        APKSIGNER=$candidate
        break
    fi
    echo "::notice title=apksigner::$candidate does not run here; trying an older one"
done
test -n "$APKSIGNER" || { echo "::error::no apksigner runs here"; exit 1; }
echo "apksigner: $APKSIGNER ($("$APKSIGNER" version))"
set +e
"$APKSIGNER" verify --verbose --print-certs "$BUILT" > "$RUNNER_TEMP/verify.txt" 2>&1
STATUS=$?
set -e
cat "$RUNNER_TEMP/verify.txt"
if [ "$STATUS" -ne 0 ]; then
    MSG=$(head -c 3500 "$RUNNER_TEMP/verify.txt" | sed 's/%/%25/g' | sed ':a;N;$!ba;s/\n/%0A/g')
    echo "::error title=apksigner (exit $STATUS)::$MSG"
    exit 1
fi
GOT=$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' "$RUNNER_TEMP/verify.txt" | head -1)
if [ -z "$GOT" ]; then
    MSG=$(grep -i -E 'signer|digest|verified' "$RUNNER_TEMP/verify.txt" | head -20 | sed ':a;N;$!ba;s/\n/%0A/g')
    echo "::error title=apksigner::no signer digest in its output: $MSG"
    exit 1
fi
echo "::notice title=signer::the APK is signed by the certificate $GOT"
if [ -n "${EXPECTED:-}" ]; then
    WANT=$(printf '%s' "$EXPECTED" | tr -d ': ' | tr 'A-F' 'a-f')
    [ "$GOT" = "$WANT" ] || { echo "::error::signed by $GOT, expected $WANT"; exit 1; }
else
    echo "::warning::no COMPANION_CERT_SHA256 variable: the certificate is not checked against an expected one"
fi
AAPT2=$(ls "$ANDROID_HOME"/build-tools/*/aapt2 | sort -V | tail -1)
if "$AAPT2" dump permissions "$BUILT" | grep -q -E 'INTERNET|NETWORK_STATE'; then
    echo "::error::the APK asks for network access"; exit 1
fi
OUT="release/axross-android-companion-$VERSION.apk"
cp "$BUILT" "$OUT"
echo "::notice title=release APK::$(sha256sum "$OUT")"
