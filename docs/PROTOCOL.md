# axross companion protocol, version 2

The protocol between axross (the desktop file manager, the **client**) and
axross-android-companion (the app on the phone, the **companion**). This
file is the definition; the app implements it, and axross's fake companion is
built from it. Machine-readable parts:

- [`protocol/schema/protocol-v2.schema.json`](../protocol/schema/protocol-v2.schema.json):
  JSON Schema (2020-12) for every envelope, every method's `params` and
  `result`, every event and the message/contact/thread objects;
- [`protocol/fixtures/`](../protocol/fixtures/): frames as bytes (hex), requests that
  must pass or fail validation with a given error, and scripted sessions
  (what the client sends, what the companion answers). The companion's JVM
  unit tests run against these fixtures, and so can the client's tests;
- [`scripts/ci/companion_client.py`](../scripts/ci/companion_client.py):
  a small reference client (Python 3, stdlib only) that the emulator smoke
  test uses.

Words: *must*, *must not*, *should*, *may* as in RFC 2119.

Version 2 (companion 0.1.1) differs from version 1 (0.1.0) in three
things: the service is given a token by the shell and both sides prove in
the handshake that they hold it (sections 4 and 9); `sms.send` takes a
`client_ref`, and a send asked for again with the same `client_ref` is not
sent again (section 7); `sms.status` can look a send up by its
`client_ref`. A companion speaks one version only: 0.1.1 and 0.1.2 speak 2.

Revision 1 of version 2 (companion 0.1.2) adds what a client of revision 0
can ignore (section 10): the companion keeps its sends on disk, so that
`sms.status` answers and a send asked for again is not sent again across
restarts of the service, process deaths and reboots; `hello` names that
store; `sms.status` says `pending` while a send is still checked; the
`sms.*` events name the `client_ref`; and `backup.export` can be paced by
the client (`window`, `backup.ack`).

## 1. Transport

- The companion listens on the **abstract** Unix domain socket
  `axross-companion` (`LocalServerSocket("axross-companion")`), never on a
  TCP port. The client reaches it through ADB:
  `adb forward tcp:<port> localabstract:axross-companion` and a TCP
  connection to `127.0.0.1:<port>` on the computer, or adb's own stream
  service `localabstract:axross-companion`.
- **Peer check.** The companion reads the peer's credentials
  (`LocalSocket.getPeerCredentials()`) before it reads a single byte and
  accepts only **uid 2000 (shell, i.e. adbd) or uid 0 (root)**. Any other
  uid is closed at once: nothing is read, nothing is written, the log says
  only "refused uid N". Other apps on the phone therefore cannot use the
  socket.
- **The client checks the companion.** While the service does not run,
  any app may listen on the abstract name `axross-companion` itself, and
  adbd would connect the client to it. So the client gives the service a
  token when it starts it (section 9: only the shell can), and the
  companion must prove in `hello` that it holds that token (section 4).
  The client sends nothing but `hello` before that proof.
- The service that listens is started by the client over ADB
  (section 9); it stops by itself five minutes after the last client left.
- At most **4 sessions** at a time. A fifth connection gets the event
  `session.closing` with reason `too_many_sessions` and is closed.
- Byte order everywhere: big-endian.

## 2. Frames

```
+----------------+--------+---------------------------+
| length: u32 BE | kind:u8| payload (length - 1 bytes) |
+----------------+--------+---------------------------+
```

- `length` counts the kind byte and the payload: `1 <= length <= 16777216`
  (16 MiB). A receiver that reads a length of 0 or above 16 MiB, or an
  unknown kind, must treat the stream as broken: the companion sends
  `session.closing` (reason `protocol_error`) and closes.
- `kind = 0x01` **JSON**: the payload is one JSON object, UTF-8, no BOM.
  JSON frames from the client are at most **4 MiB** (`limits.max_request_json`);
  a larger one is skipped unread and answered with `too_large` and
  `"id": null`; the session goes on.
- `kind = 0x02` **BLOB chunk**: binary data (MMS parts, contact photos).

```
BLOB payload:  +-------------+----------+------------------+
               | blob: u32 BE| flags: u8| data             |
               +-------------+----------+------------------+
flags bit 0 = LAST (this chunk completes the blob); other bits 0.
```

- `blob` is an id `>= 1` chosen by the **sender**; the two directions have
  separate id spaces. A blob is one or more chunks with the same id, the
  last one with `LAST` set. Senders should keep chunks at or below 1 MiB of
  data; receivers must accept any chunk that fits in a frame.
- **Blobs come first.** A blob is sent completely *before* the JSON frame
  that refers to it. A JSON value refers to a blob as
  `{"blob": <id>, "size": <bytes>, "mime": "<type>"}`; the receiver checks
  that the blob is complete and has exactly `size` bytes. The companion
  sends a blob's chunks and the JSON that refers to it back to back; a
  receiver should nevertheless accept other frames between them.
- Limits: a blob from the companion is at most 64 MiB; a blob from the
  client at most 8 MiB, and the client's completed but not yet referenced
  blobs at most 32 MiB in all (beyond that: `session.closing`,
  `protocol_error`). A blob id must not be reused while a blob with that id
  is incomplete or unreferenced. Unreferenced blobs are dropped at the end
  of a session.
- This version has no method that takes a blob from the client (MMS
  sending and MMS restore come later); the companion accepts and bounds client
  blobs already so that the framing need not change.

## 3. Messages in JSON frames

Three shapes. Unknown fields must be ignored by both sides (later minor
additions add fields, never change their meaning).

```json
{"id": 7, "method": "threads.list", "params": {"limit": 50}}
{"id": 7, "ok": true, "result": {"threads": [], "next_offset": null}}
{"id": 7, "ok": false, "error": {"code": "permission_denied", "message": "READ_SMS is not granted", "data": {"permission": "READ_SMS"}}}
{"event": "sms.sent", "data": {"send_id": 3, "index": 0, "address": "+15555215554"}}
```

- **Request**: `id` an integer `1 .. 2147483647`, unique among the
  client's requests in flight; `method` a string; `params` an object
  (optional, default `{}`).
- **Response**: exactly one per request, same `id`; `ok` true with
  `result` (an object), or false with `error` (`code`, `message`, optional
  `data`). `message` is for people and never contains message text, numbers
  or names; `code` is what a program looks at.
- **Event**: `event` and `data`, no `id`.
- Requests may be pipelined; responses come in any order. At most **8
  requests in flight** per session; the ninth is answered `busy`.
- A JSON frame that is not a JSON object, has no integer `id` or no string
  `method` is answered with `bad_request` (with `"id": null` where no id
  could be read); the session goes on.
- Integers are JSON numbers without fraction or exponent; ids of messages,
  threads, contacts, parts are 64-bit (`1 .. 2^63-1`; clients in languages
  with 53-bit numbers must parse them as 64-bit). Times are **milliseconds
  since the epoch, UTC** (MMS times, which Android keeps in seconds, are
  converted).
- JSON nesting deeper than 32 levels is `bad_request`.

## 4. Handshake

### The token

- The client makes a **token**: 32 random bytes written as 64 lowercase
  hex characters. It starts (or nudges) the service with it, over ADB
  (section 9): `am start-foreground-service ... --es token <64 hex>`.
- The service keeps the tokens it was given **in memory only** (never on
  disk, never in the log), at most **8**: a ninth drops the oldest; one
  given again counts as the newest. A malformed token (not 64 lowercase
  hex characters) is ignored. They are gone when the service stops.
- `token_id`: the first 32 hex characters of the lowercase hex SHA-256 of
  the token's 64 ASCII characters. It names the token without telling it.

### `hello` and `auth`

The client speaks first: `hello`, then `auth`, both within **10 s** of the
connection, or the companion closes it (`handshake_timeout`).

```json
{"id": 1, "method": "hello", "params": {"protocols": [2], "client": "axross", "client_version": "0.15.186",
 "client_nonce": "3dcdd07d15067a98edb7285e0077180495eee15a99a75fc773bcdf5f636a61ee",
 "token_id": "8c1b57b8b3a7a0c67ddece853a774618"}}
```

```json
{"id": 1, "ok": true, "result": {
  "protocol": 2,
  "revision": 1,
  "app": "axross-android-companion",
  "app_version": "0.1.2",
  "app_version_code": 102,
  "package": "io.github.c0decave.axross.companion",
  "android": {"sdk_int": 34, "release": "14", "manufacturer": "Google", "model": "sdk_gphone64_x86_64"},
  "capabilities": ["contacts", "contacts.photo", "threads", "messages", "messages.search", "message.mark_read", "message.delete", "sms.send", "sms.status", "mms.parts", "mms.part_data", "subscribe", "subscriptions", "backup.export", "backup.restore", "sms.refs", "backup.ack"],
  "limits": {"max_frame": 16777216, "max_request_json": 4194304, "max_blob_in": 8388608, "max_blobs_pending": 33554432, "max_blob_out": 67108864, "max_in_flight": 8, "max_recipients": 10, "max_text_chars": 5000, "max_parts": 30, "sends_per_minute": 20, "sends_per_hour": 100, "max_page": 500},
  "default_sms_app": false,
  "permissions": {"READ_SMS": true, "SEND_SMS": true, "RECEIVE_SMS": true, "READ_CONTACTS": true, "READ_PHONE_STATE": true, "POST_NOTIFICATIONS": true},
  "sends": {"store": "6feebacd6df89e21d7171200838883d5", "keep_ms": 86400000},
  "auth": {"companion_nonce": "<64 hex>", "proof": "<64 hex>"}
}}
```

```json
{"id": 2, "method": "auth", "params": {"proof": "<64 hex>"}}
{"id": 2, "ok": true, "result": {}}
```

- `protocols`: the versions the client speaks (non-empty, at most 16
  integers). The companion picks the highest it shares. With none in
  common it answers `unsupported_protocol` with `data.supported` (`[2]`)
  and closes - also when the rest of the `hello` is of an older version.
- `client`, `client_version`: strings of at most 64 characters, for the log
  and the notification; optional.
- `client_nonce` (required): 32 random bytes as 64 lowercase hex
  characters, new for every connection. `token_id` (required): 32 lowercase
  hex characters, the id of the client's token. Missing or malformed:
  `bad_request` with `field` `client_nonce` or `token_id` (the session goes
  on, within the 10 s).
- The companion looks the token up by `token_id`. Not there: the error
  **`unknown_token`** (no data), and it closes. The client then starts the
  service with its token again and tries once more.
- Else the result has, beside the rest, `auth`: `companion_nonce`, 32 fresh
  random bytes as 64 lowercase hex characters, and `proof`, the lowercase
  hex HMAC-SHA256 whose key is the token's 64 characters (their UTF-8 bytes,
  not the 32 bytes they spell), over the UTF-8 text

  ```
  axross-companion-2 companion <client_nonce> <companion_nonce>
  ```

  (single spaces, no newline).
- The client checks the proof, in constant time. A wrong or missing proof:
  the client closes and tells its user that something else answers on the
  companion's socket; it sends no other request.
- **`auth`** `{"proof": "<64 hex>"}`: the client's proof, HMAC-SHA256 with
  the same key over `axross-companion-2 client <companion_nonce>
  <client_nonce>`. Right: the result `{}`, and the session is open for every
  method. Wrong: the error **`auth_failed`**, and the companion closes. A
  proof that is not 64 lowercase hex characters: `bad_request` (`field`
  `proof`). `auth` before `hello`, or a second `auth`: `bad_request`
  (`field` `method`).
- Any method other than `hello` and `auth` before a successful `auth`:
  `handshake_required`, then the companion closes. A second `hello`:
  `bad_request` (`field` `method`).
- `capabilities` says what this companion implements. A method whose
  capability is missing answers `not_implemented`. `mms.send` is not in
  this version.
- `permissions` and `default_sms_app` are the state at the time of the
  answer (`status` reads them again). Absent `POST_NOTIFICATIONS` below
  Android 13 is reported as `true` (no such permission there).
- `revision` (since 0.1.2; absent means 0): the additions within version 2
  this companion has (section 10). The capabilities `sms.refs` (the send
  store below, `pending`, `client_ref` in the events) and `backup.ack` say
  the same per feature.
- `sends` (revision 1): the companion's **send store** (section 7,
  `sms.send`). `store` is its id, 128 random bits as 32 lowercase hex
  characters, made together with the store; `keep_ms` (86400000, a day) is
  how long a send is kept after it was accepted. The id changes only when
  the store is new, lost or unreadable: app data cleared, the app
  uninstalled and installed again, a corrupt file. It stays the same across
  runs of the service, process deaths and reboots. A client that sees
  another `store` than before must treat what it knew of earlier sends as
  unknown.

## 5. Errors

| code | when | `data` |
|---|---|---|
| `bad_request` | malformed request, a parameter missing, of the wrong type or out of range | `field`: the parameter's name |
| `unknown_method` | no such method | |
| `handshake_required` | a request before `hello` and `auth` are done (the session is closed) | |
| `unsupported_protocol` | no common version (the session is closed) | `supported` |
| `unknown_token` | `hello`'s `token_id` names no token the service was given (the session is closed) | |
| `auth_failed` | `auth`'s proof is wrong (the session is closed) | |
| `not_implemented` | known, not in this version (`mms.send`; MMS in a restore) | |
| `permission_denied` | the runtime permission it needs is not granted | `permission` |
| `not_default_sms_app` | Android lets only the default SMS app write messages (delete, mark read, restore) | `default_sms_package` (may be null) |
| `not_found` | no such message, thread, contact, part, send (by `send_id` or `client_ref`; for `client_ref` see `sms.status`), export, restore | |
| `too_many_recipients` | more than `limits.max_recipients` | `max` |
| `too_large` | a text over `max_text_chars` or more than `max_parts` parts, a frame over `max_request_json`, a part over `max_blob_out` | `max` |
| `rate_limited` | sending would exceed the send limits | `retry_after_ms` |
| `no_subscription` | `sub_id` is not an active SIM subscription | |
| `busy` | too many requests in flight; a second export or restore at a time | |
| `user_denied` | the phone's user said no (restore) | |
| `cancelled` | an export was cancelled | |
| `internal` | anything else (no details that could carry personal data); also a send that could not be written to the send store (it was not sent) | |

## 6. Objects

### Message (`kind: "sms"`)

```json
{"kind": "sms", "id": 123, "thread_id": 5, "address": "+15555215554",
 "date": 1764150000000, "date_sent": 1764149999000, "box": "inbox",
 "read": true, "seen": true, "status": "none", "body": "Hello",
 "sub_id": 1, "locked": false, "error_code": 0}
```

- `box`: `inbox`, `sent`, `draft`, `outbox`, `failed`, `queued` (Android's
  `type` 1..6), `other` for anything else.
- `status`: `none`, `complete`, `pending`, `failed` (the delivery report
  as the provider has it).
- `address` may be an empty string (drafts) or an alphanumeric sender name;
  `sub_id` is `-1` when unknown; `date_sent` is 0 when unknown.

### Message (`kind: "mms"`)

```json
{"kind": "mms", "id": 77, "thread_id": 5, "date": 1764150000000, "date_sent": 0,
 "box": "inbox", "read": false, "seen": true, "subject": null,
 "addresses": [{"address": "+15555215554", "role": "from"}],
 "text": "Look at this", "attachments": [{"part_id": 301, "mime": "image/jpeg", "name": "IMG_1.jpg"}],
 "sub_id": 1, "locked": false}
```

- `role`: `from`, `to`, `cc`, `bcc`. `text`: the `text/plain` parts joined
  by newlines (empty string if none). `attachments`: the parts other than
  `text/plain` and `application/smil`, without their data (`mms.parts`,
  `mms.part_data`).

### Cursor

`messages.list` and `messages.search` page by a cursor, newest first. The
client treats it as opaque and passes back what `next` said:
`{"date": 1764150000000, "kind": "sms", "id": 123}`. The order is `date`
descending, then SMS before MMS at the same millisecond, then `id`
descending.

### Thread

```json
{"id": 5, "date": 1764150000000, "message_count": 12, "unread_count": 2,
 "snippet": "Hello", "read": false, "has_attachment": false, "error": false,
 "archived": false,
 "recipients": [{"address": "+15555215554", "contact_id": 42, "name": "Ada"}]}
```

`contact_id` and `name` are null when no contact matches or `READ_CONTACTS`
is not granted. `snippet` may be null.

### Contact

```json
{"id": 42, "lookup_key": "0r1-2C...", "name": "Ada Lovelace", "starred": false,
 "has_photo": true,
 "numbers": [{"number": "+1 555-521-5554", "normalized": "+15555215554", "type": "mobile", "label": null}]}
```

`type`: `mobile`, `home`, `work`, `main`, `other`, `custom` (then `label`
is the user's text), or another of Android's names in lower case.
`normalized` may be null.

### Subscription (a SIM)

```json
{"sub_id": 1, "slot_index": 0, "display_name": "SIM 1", "carrier_name": "T-Mobile",
 "country_iso": "us", "is_default_sms": true}
```

## 7. Methods

Parameters in `[]` are optional, with their default. "Needs" names the
runtime permission; without it the answer is `permission_denied`.

### `hello`, `auth`, `ping`, `status`

- `hello` and `auth`: section 4.
- `ping` `{}` → `{"time": <ms>}`.
- `status` `{}` → `{"default_sms_app": bool, "default_sms_package": str|null,
  "permissions": {...as in hello}, "subscribed": [event names], "sessions": n}`.

### `subscriptions.list` (needs READ_PHONE_STATE)

`{}` → `{"subscriptions": [Subscription...], "default_sms_sub_id": int}`
(`-1` when there is no default). An empty list on a phone without a SIM.

### `contacts.list` (needs READ_CONTACTS)

`{[offset: 0], [limit: 500]}` (`limit` 1..1000) → `{"contacts": [Contact...],
"total": n, "next_offset": int|null}`, sorted by name. Contacts without a
phone number are included (`numbers: []`).

### `contacts.photo` (needs READ_CONTACTS)

`{contact_id, [size: "thumb"]}` (`"thumb"` or `"full"`) → a blob, then
`{"contact_id": 42, "photo": {"blob": 1, "size": 5120, "mime": "image/jpeg"}}`.
No photo: `not_found`.

### `threads.list` (needs READ_SMS)

`{[offset: 0], [limit: 200]}` (`limit` 1..1000) → `{"threads": [Thread...],
"next_offset": int|null}`, newest first.

### `messages.list` (needs READ_SMS)

`{thread_id, [limit: 100], [before: Cursor]}` (`limit` 1..500) →
`{"messages": [Message...], "next": Cursor|null}`, newest first; `next` is
null on the last page.

### `messages.search` (needs READ_SMS)

`{query, [thread_id], [limit: 100], [before: Cursor]}` (`query` 1..200
characters; `limit` 1..200) → as `messages.list`. A substring search in SMS
bodies and MMS text parts (SQLite `LIKE`: case-insensitive for ASCII only).

### `message.get` (needs READ_SMS)

`{kind, id}` → `{"message": Message}` or `not_found`.

### `message.mark_read` (needs READ_SMS; default SMS app)

`{kind, id, [read: true]}` or `{thread_id, [read: true]}` →
`{"updated": n}`. Android ignores writes to the SMS store from any app but
the default SMS app; the companion therefore answers `not_default_sms_app`
unless it is the default.

### `message.delete` (needs READ_SMS; default SMS app)

`{kind, id}` or `{thread_id}` → `{"deleted": n}`; `not_default_sms_app`
unless the companion is the default SMS app. The client asks its user
first; the companion does not ask again.

### `sms.send` (needs SEND_SMS)

```json
{"id": 9, "method": "sms.send", "params": {"recipients": ["+15555215554"], "text": "Hi", "sub_id": 1, "delivery_report": true,
 "client_ref": "6f1c2a4e-9b0d-4c7e-8a35-2d1f0e9b7c41"}}
```

- `recipients`: 1..**10** strings (`limits.max_recipients`), each at most
  64 characters. Spaces, `-`, `(`, `)`, `.` and `/` are removed; what is
  left must be `+` (optional) and 3..20 digits. Duplicates (after that) are
  `bad_request`. More than 10: `too_many_recipients`.
- `text`: 1..**5000** UTF-16 code units, no NUL, no unpaired surrogates; it
  is split by Android (`SmsManager.divideMessage`) and must give at most
  **30** parts per recipient (`too_large` otherwise).
- `sub_id`: optional; an active subscription's id (`no_subscription`
  otherwise); absent or `-1`: the phone's default for SMS.
- `delivery_report`: optional, default `true`.
- `client_ref`: optional; the client's name for this send, 1..64
  characters of `A-Z`, `a-z`, `0-9`, `_` and `-` (malformed: `bad_request`,
  `field` `client_ref`). axross sends one with every send (a UUID).
- **The send store** (revision 1). Every send the companion *accepts* is
  written to its send store, in the app's private files, **before** any
  part of it is handed to Android: its `client_ref`, `send_id`, the
  normalized recipients with their index and parts, the time of acceptance
  and each recipient's state and reason - never the text. The write is
  atomic (a temporary file, fsync, rename; the reports that follow go to a
  journal, fsync'd line by line). If it fails, the answer is `internal` and
  nothing is sent. The store is never in a backup and never moved to
  another device. A send is kept for `keep_ms` (a day) after acceptance and
  dropped at the latest an hour after that (the grace guards against a
  phone clock that is set forward); at most 5000 sends are kept, the oldest
  dropped first (the rate limit allows 2400 a day).
- **A send asked for again.** A `sms.send` whose `client_ref` is in the
  send store (accepted within `keep_ms`, from any session, in any run of
  the service) is **not sent again**: the answer is the stored send's
  result, with `"duplicate": true`. It does not count against the rate
  limits, and the first send's events are not sent again (the client asks
  `sms.status`). The parameters of the second request are checked, but not
  compared with the first's. A send that was refused (`rate_limited`,
  `no_subscription`, ...) was not accepted: its `client_ref` may come
  again.
- **While a send is checked** (revision 1). As soon as an `sms.send` with
  a `client_ref` has been read and its parameters have passed the checks
  above, the companion registers that `client_ref` (in memory) as being
  checked - before the permission, the SIM, the division into parts and
  the rate limit are looked at. `sms.status` then says `pending`. A second
  `sms.send` with the same `client_ref` meanwhile waits for the first: it
  is answered after the first, with the first's result and `"duplicate":
  true`, or with the same error the first got. A refused send's
  registration is dropped when its error is answered.
- **Rate limit** (all sessions together, counted per recipient): at most
  **20** messages in any 60 s and **100** in any hour; the sends of the
  store count, so the limit holds across runs of the service. A request that would
  go over is refused whole with `rate_limited` and `retry_after_ms`.
  Android has its own limit too (by default about 30 SMS in 30 minutes per
  app) and then asks the phone's user; that shows as a late `sms.sent` or
  as `sms.failed` with `reason: "limit_exceeded"`.

Result: `{"send_id": 3, "recipients": [{"index": 0, "address": "+15555215554", "parts": 1}], "client_ref": "6f1c...", "duplicate": false}`
(the addresses as normalized; `client_ref` only when the request had one).
`send_id`s do not repeat for at least `keep_ms` across runs of the service:
the store keeps a counter, started at a random value in 1..2^31 when the
store was made; ids stay below 2^53. Then, **to this session only** (for a
duplicate: none; a session that has gone gets none - the reports still go
into the store), per recipient:

- `sms.sent` `{send_id, index, address, [client_ref]}` when every part was
  handed to the network;
- `sms.failed` `{send_id, index, address, reason, result_code,
  [client_ref]}` when a part could not be: `reason` one of
  `generic_failure`, `radio_off`, `null_pdu`, `no_service`,
  `limit_exceeded`, `short_code_not_allowed`, `short_code_never_allowed`,
  `fdn_check_failure`, `other`; `result_code` Android's number;
- `sms.delivered` `{send_id, index, address, status, [client_ref]}` when
  the delivery reports of all parts are in (`status`: `complete`,
  `pending`, `failed`); only with `delivery_report` and only if the network
  sends reports.

`client_ref` (revision 1) is in each when the send had one.

When the companion is not the default SMS app, Android itself stores sent
messages in the "sent" box; when it is, the companion stores them.

### `sms.status`

`{send_id}` or `{client_ref}` (exactly one: both is `bad_request` with
`field` `client_ref`, neither `bad_request` with `field` `send_id`) →

```json
{"send_id": 3, "recipients": [{"index": 0, "address": "+15555215554", "state": "sent", "reason": null}],
 "client_ref": "6f1c...", "store": "6feebacd6df89e21d7171200838883d5", "accepted": 1764150000000}
```

(`client_ref` when the send had one). The answer comes from the send store
(revision 1; `sms.send`), so it is there also for a send of an earlier run
of the service: after a restart, a process death, a reboot. `store` is the
store's id (as in `hello`), `accepted` the time the send was accepted (the
phone's clock, ms). `state` is one of `sending`, `sent`, `failed`,
`delivered`, `delivery_failed`, `unknown`; `reason` is set for `failed` (the
values of `sms.failed`). Android's reports reach the store also while the
service does not run (a receiver in the app's manifest takes them).

- **`unknown`** (revision 1): the send was handed to Android, but its
  outcome was never recorded and will not be. The rule: a recipient that
  is still `sending` **one hour** after acceptance is `unknown` from then
  on, and a report that Android gives later is ignored (so the answer does
  not change once it said `unknown`). It happens when the process died
  between the store's write and Android taking the send, or when Android
  never reported. A client should treat it as "may have been sent".
- **`pending`** (revision 1): while an `sms.send` with that `client_ref` is
  still being checked (`sms.send`, "While a send is checked"), the answer
  is

  ```json
  {"client_ref": "6f1c...", "send_id": null, "recipients": [], "pending": true, "store": "6feebacd6df89e21d7171200838883d5"}
  ```

  and the client asks again a little later (or waits for the `sms.send`'s
  answer).
- **`not_found`**: by `send_id`, no such send in the store. By
  `client_ref`, the companion never *accepted* a send with it: it was
  refused, or it never arrived. That means "not sent" - provided that
  `hello`'s `sends.store` is the same as when the client sent it, and the
  send is younger than `keep_ms`. A client that cannot be sure of both
  (another store id, an older send, or a companion of revision 0, which
  forgot its sends when its service stopped) must say "unknown" rather than
  "not sent".

### `mms.parts` (needs READ_SMS)

`{id}` (the MMS id) → `{"message_id": 77, "parts": [{"part_id": 301, "mime": "image/jpeg", "name": "IMG_1.jpg", "content_id": "<img1>", "charset": 106, "text": null, "has_data": true}]}`.
`text` is the part's text for `text/plain` and `application/smil`, else null.

### `mms.part_data` (needs READ_SMS)

`{part_id}` → a blob, then `{"part_id": 301, "data": {"blob": 2, "size": 48211, "mime": "image/jpeg"}}`.
Over 64 MiB: `too_large`.

### `mms.send`

Not in this version (`not_implemented`). Building an M-Send.req PDU and
handing it to `SmsManager.sendMultimediaMessage` is planned; the request
will take its attachments as client blobs (section 2).

### `subscribe`, `unsubscribe`

`{events: ["message.received", "messages.changed"]}` → `{"events": [...now subscribed...]}`.
`unsubscribe` with `[events]` (default: all). Unknown event names are
`bad_request`.

### `backup.export` (needs READ_SMS)

`{[thread_id], [include_mms: true], [include_mms_data: true], [window]}` →
`{"export_id": 1, "sms_count": n, "mms_count": m}`, then the events, in
order:

- `backup.items` `{export_id, seq, items: [Message...]}` (`seq` from 0; at
  most 500 SMS or 20 MMS an event). With `include_mms_data`, every MMS item
  has `parts` (as in `mms.parts`) and each part with data has
  `"data": {"blob", "size", "mime"}`, its blob sent before the event;
- `backup.done` `{export_id, sms, mms, blobs}` at the end, or
  `backup.failed` `{export_id, code}` (`cancelled`, `permission_denied`,
  `internal`).

One export at a time per session (`busy`). `backup.cancel` `{export_id}` →
`{}` (its answer comes before the `backup.failed` it causes; `not_found`
unless it is the session's running export). The data are streamed as they
are read: a slow client slows the export, nothing piles up on the phone.

**Flow control** (revision 1). With `window` (an integer 1..64; out of
range: `bad_request`, `field` `window`), at most `window` `backup.items`
events are *outstanding* - sent, but not yet acknowledged; `backup.done`
and `backup.failed` are not counted. The client acknowledges with

- `backup.ack` `{export_id, seq}` → `{}`: every `backup.items` of that
  export up to and including `seq`. An ack of a `seq` acknowledged before
  changes nothing. A `seq` above the last one sent: `bad_request` (`field`
  `seq`); an `export_id` that is not the session's export: `not_found`.
  The answer comes before the events the ack lets go. The session's last
  export stays known until its next export or the end of the session, so
  acks that come after `backup.done` are answered `{}`.

While the export waits for acks, the session goes on reading and answering
every other request (the export waits on its own thread); it waits as long
as the session is open, and `backup.cancel` ends it. Without `window`, the
export does not wait for acks (as in revision 0).

**The backup file** (what axross writes; the companion only streams): JSON
Lines, UTF-8. Line 1:
`{"format": "axross-sms-backup", "version": 1, "created": <ms>, "device": {...hello's android...}, "app_version": "0.1.2"}`;
then one Message object per line, exactly as `backup.items` carries them,
MMS part data base64 in `"data": {"base64": "...", "mime": "..."}` instead
of a blob reference. Files with mode 0600.

### `backup.restore.begin`, `.chunk`, `.end` (needs READ_SMS; default SMS app; asks)

1. `backup.restore.begin` `{sms_count, [source]}` (`sms_count`
   1..1000000; `source` at most 100 characters, shown to the phone's user,
   e.g. a file name) → `{"restore_id": 1, "state": "waiting_for_user"}`.
   The companion shows a notification "axross wants to restore N messages";
   tapping it opens a screen with *Restore* and *Cancel*, and *Restore*
   first asks Android to make the companion the default SMS app if it is
   not. Then the event
   `backup.restore.state` `{restore_id, state}` with `state` one of
   `approved`, `denied`, `not_default_sms_app` (the user kept the other
   app), `expired` (no answer within 5 minutes).
2. `backup.restore.chunk` `{restore_id, items: [Message...]}` (at most 500
   items; only `kind: "sms"` - MMS items are counted as `rejected` in
   this version) → `{"inserted": n, "skipped": n, "rejected": n}`. `skipped`:
   already on the phone (same address, date, box and body). Only after
   `approved`, and only while the companion is still the default
   (`not_default_sms_app` otherwise).
3. `backup.restore.end` `{restore_id}` → `{"inserted": n, "skipped": n,
   "rejected": n, "previous_default_sms_package": str|null}`. The companion
   then shows a notification asking the user to make their own messaging
   app the default again (Android does not let an app give the role back by
   itself).

One restore at a time on the phone (`busy`).

## 8. Events

| event | to | data |
|---|---|---|
| `message.received` | sessions subscribed to it | `{"message": Message}` - a new message in the inbox, as stored |
| `messages.changed` | sessions subscribed to it | `{}` - something in the SMS/MMS store changed (at most one a second) |
| `sms.sent`, `sms.failed`, `sms.delivered` | the session that sent, while it is open | see `sms.send` (with `client_ref` since revision 1) |
| `backup.items`, `backup.done`, `backup.failed` | the exporting session | see `backup.export` (paced by `backup.ack` with `window`) |
| `backup.restore.state` | the restoring session | see restore |
| `session.closing` | one session | `{"reason": "..."}` - the last frame before the companion closes |

`session.closing` reasons: `service_stopping`, `fgs_timeout` (Android 15+
ended the service's daily time), `protocol_error`, `slow_reader` (the client
did not read for 60 s while frames waited), `too_many_sessions`,
`handshake_timeout` (no successful `auth` within 10 s of the connection).

`message.received` comes from a content observer on the SMS/MMS store
(started by `SMS_RECEIVED` too), so it works whether or not the companion
is the default SMS app; it reports messages that arrived while the session
was subscribed, each once.

## 9. Install and start (what axross does over ADB)

| step | command |
|---|---|
| install | `adb install -r axross-android-companion-<v>.apk` (checksum checked first; an install by ADB allows SMS permissions to be granted) |
| grant | `adb shell pm grant io.github.c0decave.axross.companion android.permission.<P>` for READ_SMS, SEND_SMS, RECEIVE_SMS, READ_CONTACTS, READ_PHONE_STATE, POST_NOTIFICATIONS (13+) |
| start | `adb shell am start-foreground-service -n io.github.c0decave.axross.companion/io.github.c0decave.axross.companion.CompanionService --es token <64 hex>` (Android 8+); the service is exported only to holders of `android.permission.DUMP`, which the shell has and apps do not. Also when it runs already: the start gives it the token (section 4) |
| connect | `adb forward tcp:0 localabstract:axross-companion`, then TCP to the port adb printed |
| start, fallback | `adb shell am start -n io.github.c0decave.axross.companion/io.github.c0decave.axross.companion.ShellStartActivity --es token <64 hex>`, where Android refuses the service's start from the shell (below) |
| open the app | `adb shell am start -n io.github.c0decave.axross.companion/.MainActivity` (when a grant failed: the user grants there; the screen takes no token) |

Debug builds (CI) are `io.github.c0decave.axross.companion.debug` and
listen on the same socket name; install one or the other. Their classes keep
the release's names, so the component is
`io.github.c0decave.axross.companion.debug/io.github.c0decave.axross.companion.CompanionService`.

Where Android refuses the service's start from the shell, `am start -n
<package>/io.github.c0decave.axross.companion.ShellStartActivity --es token
<64 hex>` starts it (since 0.1.2): an activity without a screen, not in the
launcher, that hands the token to the service and finishes at once. Like
the service it is exported only to holders of `android.permission.DUMP` -
the shell, not apps - so no app can give the service tokens of its own and
push the client's token out. `MainActivity`, the launcher's screen, which
any app may start, takes no token and no `--ez listen` any more (0.1.1's
`--ez listen true --es token` there is gone); its button starts the service
without a token.

Which permissions need the phone's user (see README, "Permissions"):
all six can be granted by `pm grant` when the app was installed by ADB;
some vendor systems (e.g. MIUI's "USB debugging (security settings)")
refuse `pm grant`, and then the user grants them in the app. Becoming the
default SMS app (restore, delete, mark read) always needs the user's tap on
the phone.

## 10. Versioning

`protocol` is an integer. Additions that old clients can ignore (new
fields, new methods, new events, new error codes, new capabilities) keep
the version; anything else raises it. Version 2 raised it because the
handshake changed (a version-1 client cannot open a session with a
version-2 companion: it gets `unsupported_protocol`). A client should treat an unknown
error code as `internal`, an unknown `box`/`status`/`reason` as `other`, and
an unknown send `state` as `unknown`.

Additions within a version are counted by `hello`'s `revision` (absent: 0)
and named by capabilities.

**Revision 1 (companion 0.1.2)**, within version 2:

- `hello`: `"revision": 1`, `"sends": {"store", "keep_ms"}`, the
  capabilities `sms.refs` and `backup.ack` (section 4);
- the send store: accepted sends written to disk before they are sent,
  kept for `keep_ms` (a day, not an hour as in revision 0), across runs of
  the service; `sms.send` with a known `client_ref` is a duplicate also
  after a restart, and one with a `client_ref` that is still being checked
  waits for it; `send_id`s do not repeat across runs; the rate limit holds
  across runs (section 7, `sms.send`);
- `sms.status`: from the store, with `store` and `accepted`; the state
  `unknown`; `pending` for a `client_ref` still being checked; `not_found`
  by `client_ref` means "not sent" while `store` is unchanged and the send
  is younger than `keep_ms` (section 7, `sms.status`);
- `sms.sent`, `sms.failed`, `sms.delivered` carry `client_ref`;
- `backup.export`'s `window` and the method `backup.ack`;
- outside the protocol: `ShellStartActivity` for the start with a token;
  `MainActivity` takes no token (section 9).

A client of revision 0 sees only fields and capabilities it ignores; the
one change it may notice is that sends are remembered for a day and across
restarts, so a `client_ref` it sends again after an hour is still a
duplicate.
