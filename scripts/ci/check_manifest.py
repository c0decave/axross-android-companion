#!/usr/bin/env python3
"""The manifest's security rules (docs/SECURITY.md), checked.

    python3 -I scripts/ci/check_manifest.py [MANIFEST.xml ...]

Default: the source manifest. In CI also the merged one the build made
(what is in the APK). Rules:

- the permissions asked for are exactly the list below: no INTERNET, no
  network state, no storage;
- the components that are exported are exactly the list below, each with
  the permission that guards it (or none, for the launcher and the compose
  screen); every other component says exported="false";
- no backup, no cleartext traffic, not debuggable (in the source).

Exits 1 with one line per finding.
"""
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent
A = "{http://schemas.android.com/apk/res/android}"
PACKAGE = "io.github.c0decave.axross.companion"

PERMISSIONS = {
    "android.permission.READ_SMS",
    "android.permission.SEND_SMS",
    "android.permission.RECEIVE_SMS",
    "android.permission.READ_CONTACTS",
    "android.permission.READ_PHONE_STATE",
    "android.permission.POST_NOTIFICATIONS",
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
}
# component -> the permission a caller needs (None: anyone, on purpose)
EXPORTED = {
    "MainActivity": None,
    "ComposeActivity": None,
    "CompanionService": "android.permission.DUMP",
    "SmsDeliverReceiver": "android.permission.BROADCAST_SMS",
    "MmsDeliverReceiver": "android.permission.BROADCAST_WAP_PUSH",
    "RespondService": "android.permission.SEND_RESPOND_VIA_MESSAGE",
}
COMPONENTS = ("activity", "activity-alias", "service", "receiver", "provider")


def short(name):
    for prefix in (PACKAGE + ".", "."):
        if name.startswith(prefix):
            return name[len(prefix):]
    return name


def check(path, problems, source):
    root = ET.parse(path).getroot()
    where = str(path)
    asked = {e.get(A + "name") for e in root.iter("uses-permission")} | {e.get(A + "name") for e in root.iter("uses-permission-sdk-23")}
    for p in sorted(asked - PERMISSIONS):
        problems.append(f"{where}: asks for {p}, which is not on the list")
    for p in sorted(PERMISSIONS - asked):
        problems.append(f"{where}: does not ask for {p}")
    app = root.find("application")
    if app is None:
        problems.append(f"{where}: no <application>")
        return
    if app.get(A + "allowBackup") != "false":
        problems.append(f"{where}: allowBackup is not false")
    if app.get(A + "usesCleartextTraffic") == "true":
        problems.append(f"{where}: cleartext traffic allowed")
    if source and app.get(A + "debuggable") is not None:
        problems.append(f"{where}: android:debuggable is set in the source")
    seen = set()
    for tag in COMPONENTS:
        for e in app.iter(tag):
            name = short(e.get(A + "name", ""))
            exported = e.get(A + "exported")
            if name in EXPORTED:
                seen.add(name)
                if exported != "true":
                    problems.append(f"{where}: {name} must be exported (Android must reach it)")
                want = EXPORTED[name]
                if e.get(A + "permission") != want:
                    problems.append(f"{where}: {name} is guarded by {e.get(A + 'permission')}, not {want}")
            else:
                if exported != "false":
                    problems.append(f"{where}: {tag} {name} must say exported=\"false\"")
    for name in sorted(set(EXPORTED) - seen):
        problems.append(f"{where}: {name} is missing")


def main():
    paths = [Path(p) for p in sys.argv[1:]] or [ROOT / "android/app/src/main/AndroidManifest.xml"]
    problems = []
    for p in paths:
        check(p, problems, source=p.name == "AndroidManifest.xml" and "src" in p.parts)
    for problem in problems:
        print(problem)
    if problems:
        return 1
    print("manifest: permissions and exported components as they should be (" + ", ".join(map(str, paths)) + ")")
    return 0


if __name__ == "__main__":
    sys.exit(main())
