# Architecture

## Layout

```
android/app/src/main/java/io/github/c0decave/axross/companion/
  protocol/          plain Java, no Android: the protocol (unit-tested on the JVM)
    Frame            framing: u32 BE length, kind, payload; blob chunks
    Json             a strict JSON reader/writer (no library)
    Session          one connection: handshake (hello, auth), requests,
                     answers, events, blobs, the writer thread and its queue,
                     closing
    Tokens           the tokens the shell gave (at most 8, in memory),
                     token_id, the nonces and HMAC proofs of the handshake
    Requests, Params every method's parameters, checked (the one gate)
    Recipients, TextRules   the rules of sms.send
    RateLimiter      sliding windows (minute, hour)
    PeerPolicy       uid 2000 / 0 (the credentials behind an interface)
    MessageOrder     the cursor's order and the provider selections after it
    SendStore        the accepted sends on disk (sends.json, atomic; a
                     journal of Android's reports): states, "unknown",
                     the store id, the send-id counter, keep_ms,
                     dropped_through (revision 2)
    SendTracker      over the store: client_refs being checked ("pending"),
                     duplicates (also across runs), the rate limit,
                     sms.status, the sms.* events and their session
    Exports          backup.export's one-at-a-time, cancel, window and acks
    Protocol, ErrorCode     constants
  CompanionService   the foreground service: LocalServerSocket, peer check, sessions, notification
  Backend            Session.Handler: what each method does on the phone
  SmsStore           the Telephony provider: threads, messages, MMS, writes
  Contacts           ContactsContract: list, photo, lookup
  Sends              the send store and tracker, once per process
  Sender, SmsStatusReceiver     SmsManager and its PendingIntents (the
                     receiver in the manifest: reports reach the store
                     also while the service does not run)
  Watcher            content observer + SMS_RECEIVED -> message.received
  Exporter           backup.export, streamed
  Restore, RestoreActivity      backup.restore.*, with the user's yes
  MainActivity       the one screen (takes no token)
  ShellStartActivity the shell's fallback start with a token (DUMP)
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
refuses the start: `am start -n .../io.github.c0decave.axross.companion.ShellStartActivity
--es token <64 hex>` starts it from an activity (translucent, finished at
once) that is guarded by DUMP as well.

**A token from the shell, a proof both ways (protocol 2).** The peer check
protects the companion from apps, but not axross from an app that listens
on the abstract name while the service is not running (adbd connects to
any listener of that name). So each start of the service carries a token
(`--es token`), which only the shell can give (DUMP); the companion proves
in `hello` that it holds it (HMAC-SHA256 over both nonces), and the client
proves the same with `auth`. The tokens stay in memory, at most eight (a
few axross windows or computers at a time), and are never logged.
0.1.1's MainActivity passed a token on too; as the launcher's activity any
app could reach it and push the client's token out with eight of its own.
Since 0.1.2 only ShellStartActivity (DUMP) does that.

**Sends by client_ref, on disk (revision 1).** A send whose answer was lost
(the link broke) must not be sent twice when axross tries again, and
axross must be able to tell "not sent" from "don't know" - also when the
service stopped in between (five minutes after the last client, or killed
by Android). So every accepted send goes into `SendStore` before a part of
it is handed to SmsManager: `no_backup/sends.json`, written whole to a
temporary file, fsync'd, renamed (a few hundred kB at most: the rate limit
allows 2400 recipients a day, the store keeps a day). Android's reports
(one PendingIntent per part and kind, with the store id, send id,
recipient and part in its data URI, and a request code unique per send,
recipient, part and kind) arrive at a receiver declared in the manifest,
so Android starts the process for them when the service is gone; each goes
into an append-only journal, one fsync'd line, replayed on the next start
(a line carries a sequence number and sends.json the last one it holds, so
a crash between the rename and the journal's removal replays nothing
twice; parts are kept as bitmasks, so a report counted twice counts once).
The store has a random id, named in `hello`: a store that was lost or
could not be read is made anew with a new id, and the client then knows
that "not_found" no longer means "not sent". The send ids count on from a
random start per store, so they do not repeat across runs. "unknown" is a
rule, not a guess: a recipient still "sending" an hour after acceptance
stays "unknown", and later reports are ignored, so an answer never changes
back.

**What was dropped, counted (revision 2).** "not_found" by `client_ref`
must not depend on a clock: the store drops sends by the phone's clock
(`keep_ms` plus the grace), the client judged the age by its own, and a
phone clock set forward made a sent message "not found", then "not sent".
So the store counts what it drops. `dropped_through`, the running maximum
of every dropped send id (by age, by the cap), is a field of sends.json:
the cap drops in the same write that adds the new send; an expiry writes
sends.json without the expired sends and with the new maximum first, and
removes them from memory only when that worked (a failed write keeps them,
found, until a write works again). So no answer and no crash shows a send
gone that `dropped_through` does not count. Send ids are given out one by
one from the counter in the same file, under the store's lock, and never
wrap; `hello` reads `next_id` under that lock - on the reader thread,
before any request of the session is read - so every send of the session
has an id at least that. The client keeps `next_id` per session: a
`not_found` with the same store and `dropped_through` below it is "never
accepted". A store written by 0.1.2 has no `dropped_through`; it is taken
as the highest id below its counter that it does not hold (ids were given
out consecutively, so every such id was dropped, or below the random
start), which may overstate it but never understates it.

The registration of a `client_ref` as "being checked" happens on the
session's reader thread, as the request is read (`Session.Handler.arrived`)
- before it is queued for a worker - so an `sms.status` that the client
sends after it can never be answered `not_found` for it. A second request
with that `client_ref` waits for the first's answer, then answers the
duplicate or the same error; a refusal ends the registration when its
error is answered.

**Exports paced by acks (revision 1).** `backup.export` with `window`
waits, on its own worker thread, until the client acknowledged enough
`backup.items`; the session's reader goes on, so a client that writes a
large backup slowly still gets its pings and other answers. Without
`window` the export is paced by the writer queue alone, as before.

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
incoming SMS while it holds the role, but an MMS that arrives meanwhile is
not received: the carrier's notification (WAP push) reaches only the
default app, and the companion only logs it and reminds the user to give
the role back.

Storing that notification (an M-Notification.ind) in the MMS provider, as
AOSP Messaging does with auto-retrieve off, so that the user's own app
could download it later, was looked at for 0.1.1 and left out. Android has
no public API for it: the companion would parse the carrier's binary PDU
itself (WSP-encoded headers, untrusted input), and write rows (`pdu` with
`m_type` 130, `addr`, the thread) whose meaning each messaging app reads its
own way - Google Messages keeps its own database and is not known to offer
a download for such a row. None of it can be tested in the emulator, and a
wrong row in the user's message store is worse than the reminder. The
time as the default SMS app stays short instead (restore, delete, mark
read), and the screens say that MMS are not received meanwhile.

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
