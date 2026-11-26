#!/usr/bin/env bash
# The release job's check of the signed APK (release.yml calls it):
#   bash scripts/ci/verify_release.sh VERSION
# Signed (apksigner), by the certificate $EXPECTED names when it is set
# (the repository variable COMPANION_CERT_SHA256), asking for no network
# permission; copied to release/axross-android-companion-VERSION.apk.
set -euo pipefail
VERSION=${1:?the version}
mkdir -p release
BUILT=android/app/build/outputs/apk/release/app-release.apk
test -f "$BUILT" || { echo "::error::no signed APK (app-release.apk) was built"; ls android/app/build/outputs/apk/release || true; exit 1; }
APKSIGNER=$(ls "$ANDROID_HOME"/build-tools/*/apksigner | sort -V | tail -1)
"$APKSIGNER" verify --verbose --print-certs "$BUILT" | tee "$RUNNER_TEMP/verify.txt"
GOT=$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' "$RUNNER_TEMP/verify.txt")
test -n "$GOT"
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
