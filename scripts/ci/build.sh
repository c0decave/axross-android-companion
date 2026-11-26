#!/usr/bin/env bash
# The CI's build job (ci.yml calls it; the logic lives here so that changing
# it needs no change of the workflow):
#
#   bash scripts/ci/build.sh
#
# - the JVM unit tests and the debug APK;
# - lint, reported (its errors and first warnings as annotations), not fatal;
# - the APK: its contents, its permissions (no network) and the merged
#   manifest's rules (scripts/ci/check_manifest.py);
# - while android/gradle/verification-metadata.xml is not committed: the file
#   Gradle would write for this build (tests, debug and release APK, lint),
#   to android/gradle/ (uploaded as an artifact) and - because artifacts and
#   logs may be out of reach - gzip + base64 in notice annotations
#   ("verification-metadata i/n"), which `gh api .../check-runs/<job>/annotations`
#   reads. Decode: concatenate the parts in order, base64 -d | gunzip.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
cd "$ROOT"
GRADLE=(gradle --no-daemon -p android)
METADATA=android/gradle/verification-metadata.xml

annotate() { # level title message (message: newlines kept)
    local msg=${3//'%'/'%25'}
    msg=${msg//$'\r'/'%0D'}
    msg=${msg//$'\n'/'%0A'}
    echo "::$1 title=$2::$msg"
}

if [ -f "$METADATA" ]; then
    echo "dependency verification: $METADATA (enforced)"
else
    echo "dependency verification: no $METADATA yet (written at the end of this job)"
fi

"${GRADLE[@]}" testDebugUnitTest
"${GRADLE[@]}" assembleDebug

if ! "${GRADLE[@]}" lintDebug; then
    annotate warning "lint" "lintDebug failed (not fatal yet)"
fi
LINT_XML=$(find android/app/build/reports -name 'lint-results-debug.xml' | head -1 || true)
if [ -n "$LINT_XML" ]; then
    python3 -I - "$LINT_XML" <<'EOF'
import sys
import xml.etree.ElementTree as ET
issues = ET.parse(sys.argv[1]).getroot().findall("issue")
order = {"Fatal": 0, "Error": 1, "Warning": 2, "Information": 3}
issues.sort(key=lambda i: order.get(i.get("severity"), 9))
counts = {}
for i in issues:
    counts[i.get("severity")] = counts.get(i.get("severity"), 0) + 1
print("lint: " + (", ".join(f"{n} {s}" for s, n in counts.items()) or "no issues"))
lines = []
for i in issues[:40]:
    loc = i.find("location")
    where = ""
    if loc is not None:
        where = loc.get("file", "").split("/app/")[-1] + ":" + loc.get("line", "?")
    lines.append(f"{i.get('severity')} {i.get('id')} {where}: {i.get('message')}")
if lines:
    msg = "\n".join(lines).replace("%", "%25").replace("\r", "").replace("\n", "%0A")
    level = "warning" if counts.get("Error") or counts.get("Fatal") else "notice"
    print(f"::{level} title=lint ({', '.join(f'{n} {s}' for s, n in counts.items())})::{msg}")
EOF
fi

APK=android/app/build/outputs/apk/debug/app-debug.apk
ls -l "$APK"
unzip -l "$APK"
sha256sum "$APK"
AAPT2=$(ls "$ANDROID_HOME"/build-tools/*/aapt2 | sort -V | tail -1)
"$AAPT2" dump permissions "$APK" | tee "$RUNNER_TEMP/permissions.txt"
if grep -q -E 'INTERNET|NETWORK_STATE' "$RUNNER_TEMP/permissions.txt"; then
    annotate error "permissions" "the APK asks for network access"
    exit 1
fi
MERGED=$(find android/app/build/intermediates -path '*debug*' -name AndroidManifest.xml -path '*merged_manifest*' | head -1)
if [ -z "$MERGED" ]; then
    annotate error "manifest" "no merged manifest found"
    exit 1
fi
python3 -I scripts/ci/check_manifest.py "$MERGED"

if [ ! -f "$METADATA" ]; then
    # (the release variant too: the release job resolves it)
    "${GRADLE[@]}" --write-verification-metadata sha256 testDebugUnitTest assembleDebug assembleRelease lintDebug
    test -s "$METADATA"
    echo "verification metadata: $(wc -c < "$METADATA") bytes, sha256 $(sha256sum "$METADATA" | cut -d' ' -f1)"
    ENCODED=$(gzip -9n < "$METADATA" | base64 -w0)
    CHUNK=30000
    PARTS=$(( (${#ENCODED} + CHUNK - 1) / CHUNK ))
    for ((i = 0; i < PARTS; i++)); do
        echo "::notice title=verification-metadata $((i + 1))/$PARTS sha256 $(sha256sum "$METADATA" | cut -c1-16)::${ENCODED:$((i * CHUNK)):$CHUNK}"
    done
fi
