# Architecture

## Layout

```
android/app/src/main/java/io/github/c0decave/axross/companion/
  protocol/          plain Java, no Android: the protocol (unit-tested on the JVM)
    Frame            framing: u32 BE length, kind, payload; blob chunks
    Json             a strict JSON reader/writer (no library)
    Session          one connection: handshake, requests, answers, events,
                     blobs, the writer thread and its queue, closing
    Requests, Params every method's parameters, checked (the one gate)
    Recipients, TextRules   the rules of sms.send
    RateLimiter      sliding windows (minute, hour)
    PeerPolicy       uid 2000 / 0 (the credentials behind an interface)
    MessageOrder     the cursor's order and the provider selections after it
    SendTracker      parts sent/delivered -> sms.* events; sms.status
    Protocol, ErrorCode     constants
  CompanionService   the foreground service: LocalServerSocket, peer check, sessions, notification
  Backend            Session.Handler: what each method does on the phone
  SmsStore           the Telephony provider: threads, messages, MMS, writes
  Contacts           ContactsContract: list, photo, lookup
  Sender, SmsStatusReceiver     SmsManager and its PendingIntents
  Watcher            content observer + SMS_RECEIVED -> message.received
  Exporter           backup.export, streamed
  Restore, RestoreActivity      backup.restore.*, with the user's yes
  MainActivity       the one screen
  SmsDeliverReceiver, MmsDeliverReceiver, ComposeActivity, RespondService
                     what Android requires of a default SMS app
  Perms, Notes, L    permissions and the role, notifications, the log
android/app/src/test/...   JVM unit tests, also against protocol/fixtures
protocol/            schema and fixtures (shared with axross)
scripts/             check_pins.py, smoke_test.sh, ci/ (the reference client,
                     the protocol and manifest checks)
```

The split is the point: everything a client can send is handled by the
`protocol` package, which has no Android in it and is tested on the JVM -
including against the same fixtures axross's fake companion uses. The
Android classes only read and write the phone's data.

## Decisions

**Foreground service type dataSync.** connectedDevice would describe a
computer on USB best, but on Android 14+ it requires one of Bluetooth,
USB device/accessory, network-state or NFC/UWB permissions, none of which
an ADB link gives (and the app wants no network permission). dataSync has no
prerequisite; Android 15+ limits it to six hours a day, after which
`onTimeout` closes the sessions (`session.closing`, reason `fgs_timeout`)
and the service stops. `specialUse` would have no limit; it can replace
dataSync if the limit hurts.

**Started by the shell.** The service is exported with
`android:permission="android.permission.DUMP"`: the shell (and root) hold
DUMP, apps cannot (it is signature/privileged/development). Even if
something started it, only uid 2000/0 can connect. Fallback where Android
refuses the start: `am start -n .../.MainActivity --ez listen true` starts
it from the visible activity.

**One writer thread a session, a bounded queue.** A client that stops
reading stalls no other session and no producer: live events are queued at
once (up to 96 MiB, then the session is closed as `slow_reader`); answers
and an export's events wait for the queue (16 MiB) at most 60 s.

**Blobs before the JSON that refers to them**, in both directions: the
receiver always has the bytes when it reads the reference.

**No library.** Not even AndroidX: the app uses the framework's classes
only, so the APK holds this repository's code and nothing else.

**Default SMS app components.** Delete, mark read and restore need the role,
so the app declares what Android requires of a role holder. It stores
incoming SMS while it holds the role (nothing gets lost) but cannot download
MMS (version 1), and it reminds the user to give the role back.

### Versions

| What | Version | Why |
|---|---|---|
| Android Gradle Plugin | 8.13.2 | supports compileSdk 36; the 8.13 line axross-amp-android uses, its latest patch |
| Gradle | 8.14.6 | what AGP 8.13 was made for (8.14.7/8.14.8 younger than two weeks) |
| JDK | 17 | what AGP 8 runs on |
| compileSdk / targetSdk | 36 | the current platform |
| minSdk | 26 | startForegroundService, notification channels, adaptive icons |
| JUnit | 4.13.2 | tests only |

## CI

The workflows only set up and call scripts: the build job's work is in
`scripts/ci/build.sh`, the smoke test's in `scripts/smoke_test.sh`, the
release's check of the signed APK in `scripts/ci/verify_release.sh`
(changing those needs no change of `.github/`). They write what matters as
annotations (lint's findings, the app's log and the telephony side of the
smoke test, the signer, the APK's checksum, the dependency checksums until
they are committed), which `gh api repos/OWNER/REPO/check-runs/JOB/annotations`
reads where logs and artifacts are out of reach.

## Supply chain

- **Actions**: pinned by commit SHA with a version comment
  (`scripts/check_pins.py`).
- **Gradle build**: exact plugin and dependency versions, the known
  repositories only, no library in the app (also `check_pins.py`).
- **Gradle's dependencies** (AGP and its tree, aapt2, lint, JUnit): verified
  by SHA-256, `android/gradle/verification-metadata.xml` (Gradle refuses
  any artifact not in it, or with another checksum). It was written by CI
  run 45801479769 for the tests, the debug and release APK and lint (287
  components, 496 files) and reviewed: only Google's Maven and Maven Central
  artifacts of the AGP 8.13.2 tree; 22 of them, sampled, downloaded again
  and their SHA-256 compared - all equal. To change a version: delete the
  file, push, take the new one from the build job's annotations
  (`verification-metadata i/n`, xz + base64; scripts/ci/build.sh), review,
  commit.
- **Tools in CI**: the JDK (setup-java, Temurin), Gradle (Gradle's action),
  the SDK and emulator images (the runner's image, the emulator runner) -
  trusted as distributed, as in axross-amp-android.
