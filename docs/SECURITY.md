# Security

Messages and contacts are personal data. The companion's rules, and where
each is enforced.

## Who can talk to it

- **No network.** The manifest asks for no INTERNET, no network state, no
  storage (`scripts/ci/check_manifest.py` fails the build otherwise; the CI
  also checks the built APK with `aapt2 dump permissions`).
- **An abstract local socket**, `axross-companion`: no TCP port, nothing on
  the network. A computer reaches it only through ADB (`adb forward ...
  localabstract:axross-companion`), i.e. with USB debugging on and the
  computer's key accepted on the phone.
- **The peer's uid** (`LocalSocket.getPeerCredentials()`) is read before a
  byte is read or written: only 2000 (shell - adbd) and 0 (root) are served
  (`PeerPolicy`, unit-tested). Any other uid - every app on the phone - is
  closed at once and logged as "refused a connection from uid N", without
  data. A failing credentials check refuses too.
- **The client checks the companion** (protocol 2). While the service is
  not running, any app could listen on the abstract name itself. axross
  therefore starts the service with a token (`--es token`, 32 random
  bytes); only the shell can give one, as the service is guarded by DUMP.
  In `hello` the companion proves that it holds the token (HMAC-SHA256
  over the client's and its own nonce); axross sends nothing else before
  it has checked that proof, and proves itself with `auth` (a wrong proof
  closes the session). The tokens are kept in memory only (at most 8, gone
  when the service stops), never logged, compared in constant time
  (`Tokens`, unit-tested). Only the shell can hand the service a token:
  directly, or through ShellStartActivity, which is guarded by DUMP as the
  service is. MainActivity, the launcher's and so reachable by any app,
  takes no token since 0.1.2 (0.1.1's took one, which let an app push the
  client's token out with tokens of its own).
- At most 4 sessions; the handshake (`hello` and `auth`) must be done
  within 10 s.

## What is exported

| Component | Exported | Guarded by | Why |
|---|---|---|---|
| MainActivity | yes | - | the launcher; takes nothing from its intent (no token) |
| CompanionService | yes | `android.permission.DUMP` | started by the shell (`am start-foreground-service --es token ...`); apps do not hold DUMP |
| ShellStartActivity | yes | `android.permission.DUMP` | the shell's fallback start with a token (`am start ... --es token ...`); no intent filter, not in the launcher, no screen |
| SmsDeliverReceiver | yes | `BROADCAST_SMS` | required of a default SMS app; only the system sends it |
| MmsDeliverReceiver | yes | `BROADCAST_WAP_PUSH` | the same |
| RespondService | yes | `SEND_RESPOND_VIA_MESSAGE` | the same; drops every request |
| ComposeActivity | yes | - | required of a default SMS app (SENDTO); shows a text, reads nothing, sends nothing |
| RestoreActivity | no | | opened from the app's own notification |
| SmsStatusReceiver | no | | the app's own PendingIntents (in the manifest: they reach the send store also while the service does not run) |

`SMS_RECEIVED` is registered at run time, with `BROADCAST_SMS` as the
sender's permission, and only nudges a re-read of the store.

## Sending

- Only `sms.send` of a connected, authenticated session sends. No intent, no
  activity, no service sends anything for another app (ComposeActivity and
  RespondService exist because Android requires them of an SMS app; they
  send nothing).
- Checked before anything: 1..10 recipients (`too_many_recipients` above),
  each an optional `+` and 3..20 digits after removing spaces and `-().\/`,
  no duplicates; text 1..5000 UTF-16 units, no NUL, no unpaired surrogate,
  at most 30 parts; `sub_id` an active subscription.
- Rate limit, all sessions together, counted per recipient: 20 in any 60 s,
  100 in any hour (`RateLimiter`, unit-tested); the sends in the send store
  count, so a restart of the service does not reset it. Android's own limit
  (about 30 SMS in 30 minutes) asks the phone's user beyond that.
- Every accepted send is written to the send store before it is handed to
  Android; a send that cannot be written is not sent (`internal`). A send
  asked for again with a `client_ref` the store has (a day, across runs of
  the service) is answered with the first send's result and not sent again
  (nor counted); one asked for while the first is still checked waits for
  it (`SendStore`, `SendTracker`, unit-tested).
- Writes to the message store (delete, mark read, restore) only as the
  default SMS app, which only the user can make it; a restore also asks the
  user on the phone first.

## Input

Every request goes through `Requests.parse` (unit-tested against
`protocol/fixtures/requests.json`): types, ranges, lengths, enums; ids are
positive 64-bit integers; frames at most 16 MiB, a request at most 4 MiB,
JSON at most 32 levels deep and strict (no duplicate keys); client blobs at
most 8 MiB each and 32 MiB pending. Provider queries take values as
arguments, never spliced into SQL (the exceptions are id lists the provider
itself returned).

## Logs and data

- The log (tag `axross-companion`) holds fixed texts, method names, counts,
  error codes and uids - never message text, numbers, names, tokens,
  nonces, proofs, client_refs or anything else read from a request (`L`). Error messages sent to the client do not carry them
  either; an unexpected exception is answered "internal" without its
  message. The smoke test checks the app's log lines for the number, the
  text it sent and its tokens.
- The app keeps one file of its own (since 0.1.2): the **send store**,
  `no_backup/sends.json` and `no_backup/sends.journal` in its private
  storage. It holds, for each SMS the companion sent in the last day (at
  most 5000), the client_ref, the send id, the recipients' numbers (as
  normalized), the number of parts, the time and each recipient's state -
  **never the text**. A send is dropped a day (plus an hour) after it was
  accepted. Nothing is backed up or moved to another device
  (`allowBackup="false"`, `no_backup/`, data extraction rules that exclude
  everything); clearing the app's data or uninstalling it deletes the
  store. The text of a send is held in memory only while the companion,
  as the default SMS app, still has to store the sent message itself. The
  tokens are kept in memory while the service runs.
