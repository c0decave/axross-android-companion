# Changelog

## 0.1.3 (2026-11-26)

Protocol 2, revision 2: additions that a client of revision 1 (companion
0.1.2's) can ignore; `protocol` stays 2. From release review 3 of axross
0.15.186's Messages (N3-M3): `not_found` by `client_ref` was believed by
the computer's clock while the companion dropped sends by the phone's, so a
phone clock set forward made a sent message "not sent", and Send Again sent
it twice. Now the companion says what it dropped, and no clock is needed.

- `hello`: `"revision": 2`; `sends` is `{"store", "keep_ms", "next_id",
  "dropped_through"}`: `next_id` the id the next accepted send gets (read
  under the lock send ids are given out under, so every send accepted
  after the `hello` has an id at least that), `dropped_through` the highest
  send id the store ever dropped - by age, by the 5000-send cap, for any
  reason - or 0. The new capability `sms.dropped`.
- `status` has `sends` too, the same object, read at the time of the answer.
- `sms.status` by `client_ref`: `not_found` carries `"data": {"store",
  "dropped_through"}`. The client records `F` = `hello`'s `sends.next_id`
  for the session it sends over; `not_found` means "never accepted, not
  sent" exactly when the store is the same and `dropped_through < F`
  (docs/PROTOCOL.md, `sms.status`). `pending` is unchanged.
- The send store keeps `dropped_through` in `sends.json`: the running
  maximum of dropped ids, never lowered, reset only with a new store id. It
  is written before or together with the removal it counts (the cap's drop
  in the same write as the new send; an expiry's file is written first and
  the sends removed from memory only when that worked; a failed write
  keeps them, found, until a write works), so neither an answer nor a
  crash shows a send gone that it does not count. A store of 0.1.2 counts
  the highest id below its counter that it does not hold. Send ids no
  longer wrap (past 2^53 - 1, `sms.send` answers `internal`).
- Fixtures: every hello of revision 2; `not_found` by `client_ref` with its
  data; `status` with `sends`; new sessions `status_dropped_clock_jump`
  (the session's send dropped after a jump of the phone's clock:
  `dropped_through` >= F) and `status_dropped_before_session` (a refused
  send: `dropped_through` < F); a new step `phone_clock` and
  `sends.dropped_through` (protocol/README.md); the schema's `Sends`,
  `SendNotFound` and `x-error-data`. The reference client and the smoke
  test check `next_id`, `dropped_through` and the proof of "never
  accepted", also after the restart.

## 0.1.2 (2026-11-26)

Protocol 2, revision 1: additions that a client of revision 0 (axross
0.15.185/0.15.186 as released) can ignore; `protocol` stays 2. From the
release review of axross 0.15.186's Messages (N-H2, N-H3, N-M1, N-L3,
N-L4, N-L5).

- **The send store.** Every accepted send is written to the app's private
  files before any part of it is handed to Android (atomically: a
  temporary file, fsync, rename; Android's reports go to a journal, one
  fsync'd line each): client_ref, send_id, the normalized recipients with
  their parts, the time, each recipient's state - never the text. Kept for
  a day (`keep_ms` 86400000, an hour of grace), at most 5000 sends, never
  in a backup. A send that cannot be written answers `internal` and is not
  sent. So `sms.status` answers, and a `client_ref` asked for again is a
  duplicate, across restarts of the service, process deaths and reboots;
  the rate limit holds across runs too.
- `hello` says `"revision": 1` and `"sends": {"store", "keep_ms"}`: the
  store's random 128-bit id, new only when the store is new, lost or
  unreadable. New capabilities `sms.refs` and `backup.ack`.
- `sms.status` answers from the store, also for earlier runs' sends, with
  `store` and `accepted`; the new state `unknown` (a recipient still
  `sending` an hour after acceptance, from then on); and, while an
  `sms.send` with that `client_ref` is still being checked,
  `{"client_ref", "send_id": null, "recipients": [], "pending": true,
  "store"}`. A `client_ref` is registered as soon as its request is read,
  before the permission, SIM, parts and rate checks, and dropped when the
  send is refused: `not_found` by `client_ref` now means "never accepted,
  not sent" while `sends.store` is the same and the send is younger than
  `keep_ms`.
- A second `sms.send` with a `client_ref` that is still being checked waits
  for the first and gets its result (`"duplicate": true`) or the same
  error.
- `sms.sent`, `sms.failed` and `sms.delivered` carry `client_ref`. Android's
  reports reach the store through the manifest's receiver also while the
  service does not run.
- Send ids count on from a random start (1..2^31) per store and do not
  repeat across runs; PendingIntents have a data URI and request code of
  their own per store, send, recipient, part and kind, so a new run never
  gets an old FLAG_ONE_SHOT intent back.
- `backup.export` takes `window` (1..64): at most that many `backup.items`
  unacknowledged; the new method `backup.ack {export_id, seq}` acknowledges
  them. The session goes on reading and answering while the export waits.
- Tokens from the shell only: `MainActivity` (the launcher's, so any app's)
  takes no token and no `--ez listen` any more. The fallback start is the
  new `ShellStartActivity`, exported only to holders of DUMP, without a
  screen: `am start -n <package>/io.github.c0decave.axross.companion.ShellStartActivity --es token <64 hex>`.
- Fixtures: new sessions `hello_revision`, `send_events_ref`,
  `status_earlier_run`, `status_pending`, `status_not_found`,
  `send_duplicate_across_runs`, `backup_window`, `backup_ack_errors`; every
  hello of revision 1; `send` and `send_duplicate` with large send ids and
  `client_ref` in their events; `sms.status` answered from the store (a
  session's `phone.sends`, an answer's `wait_for`: protocol/README.md). The
  reference client and the smoke test check the revision, the events'
  `client_ref`, an export paced by acks, a restart of the service in a new
  process, the fallback start and that `MainActivity` takes no token.

## 0.1.1 (2026-11-26)

Protocol version 2 (docs/PROTOCOL.md). axross must speak it too: a client
of version 1 gets `unsupported_protocol`.

- The client checks the companion. axross starts the service with a token
  (`am start-foreground-service ... --es token <64 hex>`; only the shell
  may start it). The service keeps the last eight tokens in memory only.
  In `hello` the client names its token by `token_id` and sends a nonce;
  the companion answers with a nonce and an HMAC-SHA256 proof made with
  the token, and the client proves itself with the new method `auth`. So
  an app that listens on `localabstract:axross-companion` while the
  service is not running gets no message, query or backup from axross.
- New error codes `unknown_token` (the service was not given that token)
  and `auth_failed` (a wrong proof); both close the session. Every method
  but `hello` and `auth` before a successful `auth` is
  `handshake_required`. The 10 s handshake time-out covers both.
- `sms.send` takes an optional `client_ref`. A send whose `client_ref` was
  accepted within the hour is not sent again: the answer is the first
  send's result with `"duplicate": true`; it does not count against the
  rate limits and its events are not sent again. Results say
  `"duplicate": false` otherwise.
- `sms.status` takes `{"client_ref": ...}` instead of `{"send_id": ...}`;
  its result names the `client_ref`.
- `MainActivity --ez listen true --es token <64 hex>` hands the token to the
  service, for phones where the shell may not start it.
- The fixtures (protocol/) are of version 2: every session starts with
  `hello` and `auth` and gives the phone's token; new sessions
  `auth_fails`, `unknown_token`, `before_auth`, `send_duplicate`. The
  schema is `protocol/schema/protocol-v2.schema.json`. The reference client
  takes `--token`; the smoke test starts the service with a token.

Still not in it: downloading MMS while the companion is the default SMS app
(an MMS that arrives then is not received; see docs/ARCHITECTURE.md).

## 0.1.0 (2026-11-26)

The first version: axross's companion on the phone, over ADB.

- Protocol version 1 (docs/PROTOCOL.md): length-prefixed JSON frames and
  binary blob frames on the abstract socket `localabstract:axross-companion`,
  a version handshake, request ids, events; a JSON Schema and fixtures
  (protocol/) that the app's tests and axross's fake companion share.
- Only uid 2000 (shell, i.e. ADB) and 0 may connect; others are closed at once.
- A foreground service (dataSync) with the notification "axross is
  connected"; started by axross over ADB (`am start-foreground-service`,
  guarded by DUMP) and stopping itself five minutes after the last client.
- Contacts (with numbers and photos), SIM subscriptions, threads (with
  unread counts and contact names), messages of a thread (SMS and MMS, paged
  by cursor), search, one message, MMS parts and their data.
- Sending SMS: up to 10 recipients, 5000 characters, 30 parts; a choice of
  SIM; `sms.sent` / `sms.failed` / `sms.delivered` events; `sms.status`; at
  most 20 messages a minute and 100 an hour.
- `message.received` and `messages.changed` events (a content observer,
  nudged by SMS_RECEIVED), whether or not the app is the default SMS app.
- Mark read and delete - as the default SMS app only (`not_default_sms_app`
  otherwise, as Android forbids it).
- Backup: a streamed export of every SMS and MMS (with the MMS parts' data);
  a restore of SMS that the phone's user approves on the phone, as the
  default SMS app.
- No network permission; nothing exported but the launcher and what
  Android needs of an SMS app (each guarded by a system permission); no
  logging of message text, numbers or names.
- CI: the pins, the protocol's consistency, the manifest's rules, the JVM
  unit tests, the debug APK, lint, an emulator smoke test (API 34; API 26
  reported). A release workflow that signs with a key from the repository's
  secrets and drafts a release.

Not in it yet: sending MMS, restoring MMS, downloading MMS while the
companion is the default SMS app, editing contacts.
