# axross-android-companion

A small open-source Android app that [axross](https://github.com/c0decave/axross),
the multi-protocol file manager, installs on a phone over ADB. It holds the
SMS and contacts permissions and does what Android does not let a computer
do over ADB: **read** SMS, MMS and contacts reliably, **send** SMS, and
report new messages as they arrive. axross's Messages window talks to it
through `adb forward` - nothing else can.

**Status: 0.1.0, a pre-release.** CI builds it, tests it on the JVM and
runs it in emulators (Android 14 and 8.0); it has not yet run on a real
phone (see [What is verified](#what-is-verified)).

- Java only, no library in the APK, minSdk 26 (Android 8.0), targetSdk 36.
- Application id `io.github.c0decave.axross.companion` (CI's debug builds:
  `io.github.c0decave.axross.companion.debug`).
- The protocol: [docs/PROTOCOL.md](docs/PROTOCOL.md), with a JSON Schema and
  fixtures in [protocol/](protocol/); the security design:
  [docs/SECURITY.md](docs/SECURITY.md); how it is built:
  [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## What it does

| | |
|---|---|
| Contacts | names, numbers (with types), photos (`contacts.list`, `contacts.photo`) |
| SIMs | the active subscriptions, which one is the default for SMS |
| Conversations | threads with contact names, last message, unread count; a thread's SMS and MMS, newest first, paged; search; one message |
| MMS | the parts of a message and their data (pictures, audio, text, vCards) |
| Sending | SMS to 1..10 recipients, on a chosen SIM, multipart; `sms.sent`, `sms.failed`, `sms.delivered` events |
| New messages | `message.received` (and `messages.changed`) while axross is subscribed - also when another app is the default SMS app |
| Mark read, delete | only as the default SMS app (Android ignores other apps' writes): `not_default_sms_app` otherwise |
| Backup | export of everything, streamed; restore of SMS, after the phone's user agreed on the phone, as the default SMS app |

Later: sending MMS (`mms.send` answers `not_implemented`), restoring MMS,
downloading MMS while the companion is the default SMS app, editing contacts.

## Install and connect (what axross does)

```sh
adb install -r axross-android-companion-0.1.0.apk
for p in READ_SMS SEND_SMS RECEIVE_SMS READ_CONTACTS READ_PHONE_STATE POST_NOTIFICATIONS; do
  adb shell pm grant io.github.c0decave.axross.companion android.permission.$p
done
adb shell am start-foreground-service -n io.github.c0decave.axross.companion/.CompanionService
adb forward tcp:38999 localabstract:axross-companion
python3 -I scripts/ci/companion_client.py --port 38999 call threads.list '{"limit": 5}'
```

The phone shows "axross is connected" while a client is attached; the
service stops by itself five minutes after the last one left. Release
assets: `axross-android-companion-<version>.apk` and `SHA256SUMS` (with
provenance attestations); axross checks the checksum before it installs.

### Permissions: which need the phone's user

| Permission | What for | `pm grant` over ADB? |
|---|---|---|
| READ_SMS | threads, messages, MMS, search, export | yes, when installed by `adb install`¹ |
| SEND_SMS | `sms.send` | yes¹ |
| RECEIVE_SMS | `SMS_RECEIVED` (new messages at once) | yes¹ |
| READ_CONTACTS | contacts, names in threads | yes |
| READ_PHONE_STATE | the SIMs (dual SIM), a send on a chosen SIM | yes |
| POST_NOTIFICATIONS (Android 13+) | the "connected" notification, the restore question | yes |
| FOREGROUND_SERVICE, FOREGROUND_SERVICE_DATA_SYNC | the listening service | install-time, nothing to grant |
| **The default SMS app role** | delete, mark read, restore | **no - always the user's tap on the phone** |

¹ SMS permissions are "hard restricted" since Android 10: they can be
granted only to an app whose installer allowed them. `adb install` (and
`pm install`) allows them unless `--restrict-permissions` is given; an APK
installed from a browser or a file manager cannot get them by `pm grant`.
Some vendor systems refuse `pm grant` altogether (MIUI/HyperOS: the
developer option "USB debugging (Security settings)"); then axross opens
the app (`adb shell am start -n io.github.c0decave.axross.companion/.MainActivity`)
and the user taps "Grant permissions".

Becoming the default SMS app is never done silently: the restore screen
asks Android, which asks the user; afterwards a notification asks the user
to make their own messaging app the default again (Android does not let an
app give the role back). While the companion is the default it stores
incoming SMS but cannot download MMS - keep that time short.

## Security in short

No INTERNET permission (no network access at all); the socket is abstract
and accepts only uid 2000 (shell/adbd) and 0 (root); nothing is exported
but the launcher, the listening service (guarded by DUMP, which the shell
has and apps do not) and the components Android requires of an SMS app
(each guarded by a permission only the system holds); every send comes from
a connected axross session, never from an intent; every request is checked
(sizes, ids, recipients; at most 10 recipients, 20 messages a minute, 100
an hour); no message text, number or name is logged. Details:
[docs/SECURITY.md](docs/SECURITY.md).

## Build

You need JDK 17, Gradle 8.14.x and the Android SDK (platform 36) with
`ANDROID_HOME` set.

```sh
gradle -p android testDebugUnitTest      # the JVM unit tests (protocol, checks, fixtures)
gradle -p android assembleDebug          # -> android/app/build/outputs/apk/debug/app-debug.apk
python3 -I scripts/ci/check_protocol.py  # schema, fixtures and docs/PROTOCOL.md agree
python3 -I scripts/ci/check_manifest.py  # permissions and exported components
bash scripts/smoke_test.sh android/app/build/outputs/apk/debug/app-debug.apk   # an emulator
```

There is no Gradle wrapper in the repository (`gradle wrapper
--gradle-version 8.14.6` inside `android/` makes one; the CI installs that
Gradle itself).

## Signing

The release workflow signs the APK with a key **you** make and keep; no key
is generated by any script here and none belongs in the repository
(`.gitignore` refuses the usual file names). An app can only ever be
updated by an APK signed with the same key, so keep it safe:

```sh
keytool -genkeypair -v -keystore axross-companion-release.jks -storetype PKCS12 \
  -alias axross-companion -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 axross-companion-release.jks        # the value of COMPANION_KEYSTORE_BASE64
keytool -list -v -keystore axross-companion-release.jks -alias axross-companion | grep SHA256
```

On GitHub (Settings → Environments → **release**; create it, and consider
"Required reviewers") add these **environment secrets**:

| Secret | What it is |
|---|---|
| `COMPANION_KEYSTORE_BASE64` | the keystore file (PKCS12 or JKS), base64 in one line |
| `COMPANION_KEYSTORE_PASSWORD` | the keystore's password |
| `COMPANION_KEY_ALIAS` | the key's alias in it (`axross-companion` above) |
| `COMPANION_KEY_PASSWORD` | the key's password (with PKCS12: the keystore's) |

and this **repository variable** (Settings → Secrets and variables →
Actions → Variables):

| Variable | What it is |
|---|---|
| `COMPANION_CERT_SHA256` | the certificate's SHA-256 (from `keytool -list -v`, colons or not): the release fails if the APK is signed by any other certificate; axross can pin the same value |

A release: set `companion.version` in `android/gradle.properties`, give
`CHANGELOG.md` its `## X.Y.Z (date)` section, tag `vX.Y.Z` and push the
tag. `release.yml` runs the whole CI first, builds and signs the APK,
verifies the signature (and that it asks for no network permission),
attests the provenance and makes a **draft** release with the APK and
`SHA256SUMS`; you look at it and publish.

## CI and the supply chain

`.github/workflows/ci.yml`, on every push:

1. **checks** - `scripts/check_pins.py` (every action pinned by commit SHA,
   exact Gradle versions, no library in the app),
   `scripts/ci/check_protocol.py`, `scripts/ci/check_manifest.py`.
2. **build** - the JVM unit tests, the debug APK, lint (reported), the
   merged manifest's rules and the APK's permissions (`aapt2 dump
   permissions`: no INTERNET).
3. **smoke** - the APK in an emulator (`scripts/smoke_test.sh`): `pm grant`,
   `am start-foreground-service`, `adb forward`, the reference client:
   hello, status, SIMs, contacts, threads, the refusals, a second session
   before hello, a broken frame, an SMS to the emulator's own number and its
   `sms.sent`; then the process alive, no crash, and no text or number in
   the app's log. Android 14 must pass; Android 8.0 is run and reported.

Gradle's own dependencies (the Android Gradle Plugin's tree from Google's
Maven repository, JUnit from Maven Central) are verified by SHA-256
(`android/gradle/verification-metadata.xml`, docs/ARCHITECTURE.md, "Supply
chain"). The APK itself contains only this repository's classes.

## What is verified

In CI (first green run 45792356217; with Gradle's checksums enforced
45803878703), on every push:

- the JVM unit tests (42, including every shared fixture), the debug APK,
  lint (one finding: OldTargetApi - targetSdk 36 is the highest the Android
  Gradle Plugin 8.13 supports), the APK's permissions (no network) and its
  merged manifest's rules, Gradle's dependencies by SHA-256;
- in emulators, Android 14 (required) and Android 8.0: install, `pm grant`
  of every permission, `am start-foreground-service` from the shell, the
  connection through `adb forward` from uid 2000, hello, status, SIMs,
  contacts, threads, the refusals (too many recipients, bad numbers, empty
  text, `mms.send`, delete and mark read without the SMS role), a request
  before hello and a broken frame on other sessions, an SMS to the
  emulator's own number with its `sms.sent` and `sms.status`, an incoming
  SMS (from the emulator's console) as `message.received`, the sent SMS in
  a thread, `messages.search`, `message.get`, a streamed `backup.export`;
  afterwards the process alive, no crash, and neither the number nor the
  text in the app's log.

Seen in the emulators and not failures: no delivery report (Android 8.0's
emulator sends none within 20 s), and Android 14's emulator does not loop an
SMS to its own number back to its inbox (the injected SMS covers
`message.received`).

Not verified yet: real phones and vendors' message stores (threads, MMS
parts), MMS at all, the default-SMS-app flow (restore, delete, mark read),
dual SIM, and Android 15+'s dataSync time limit.

## Licence

Apache-2.0 ([LICENSE](LICENSE)), as axross. The APK carries the licence text
(`assets/licenses/LICENSE`).
