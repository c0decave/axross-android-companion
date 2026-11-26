# axross companion protocol, version 1

The protocol between axross (the desktop file manager, the **client**) and
axross-android-companion (the app on the phone, the **companion**). This
file is the definition; the app implements it, and axross's fake companion is
built from it. Machine-readable parts:

- [`protocol/schema/protocol-v1.schema.json`](../protocol/schema/protocol-v1.schema.json):
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
- Version 1 has no method that takes a blob from the client (MMS sending
  and MMS restore come later); the companion accepts and bounds client
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

The client speaks first. Its first request must be `hello`; within **10 s**
of the connection, or the companion closes it.

```json
{"id": 1, "method": "hello", "params": {"protocols": [1], "client": "axross", "client_version": "0.15.183"}}
```

```json
{"id": 1, "ok": true, "result": {
  "protocol": 1,
  "app": "axross-android-companion",
  "app_version": "0.1.0",
  "app_version_code": 100,
  "package": "io.github.c0decave.axross.companion",
  "android": {"sdk_int": 34, "release": "14", "manufacturer": "Google", "model": "sdk_gphone64_x86_64"},
  "capabilities": ["contacts", "contacts.photo", "threads", "messages", "messages.search", "message.mark_read", "message.delete", "sms.send", "sms.status", "mms.parts", "mms.part_data", "subscribe", "subscriptions", "backup.export", "backup.restore"],
  "limits": {"max_frame": 16777216, "max_request_json": 4194304, "max_blob_in": 8388608, "max_blobs_pending": 33554432, "max_blob_out": 67108864, "max_in_flight": 8, "max_recipients": 10, "max_text_chars": 5000, "max_parts": 30, "sends_per_minute": 20, "sends_per_hour": 100, "max_page": 500},
  "default_sms_app": false,
  "permissions": {"READ_SMS": true, "SEND_SMS": true, "RECEIVE_SMS": true, "READ_CONTACTS": true, "READ_PHONE_STATE": true, "POST_NOTIFICATIONS": true}
}}
```

- `protocols`: the versions the client speaks (non-empty, at most 16
  integers). The companion picks the highest it shares; with none in common
  it answers `unsupported_protocol` with `data.supported` (e.g. `[1]`) and
  closes.
- `client`, `client_version`: strings of at most 64 characters, for the log
  and the notification; optional.
- `capabilities` says what this companion implements. A method whose
  capability is missing answers `not_implemented`. `mms.send` is not in
  version 1.
- `permissions` and `default_sms_app` are the state at the time of the
  answer (`status` reads them again). Absent `POST_NOTIFICATIONS` below
  Android 13 is reported as `true` (no such permission there).
- Any other method before `hello`: `handshake_required`, then the
  companion closes. A second `hello`: `bad_request`.

## 5. Errors

| code | when | `data` |
|---|---|---|
| `bad_request` | malformed request, a parameter missing, of the wrong type or out of range | `field`: the parameter's name |
| `unknown_method` | no such method | |
| `handshake_required` | a request before `hello` (the session is closed) | |
| `unsupported_protocol` | no common version (the session is closed) | `supported` |
| `not_implemented` | known, not in this version (`mms.send`; MMS in a restore) | |
| `permission_denied` | the runtime permission it needs is not granted | `permission` |
| `not_default_sms_app` | Android lets only the default SMS app write messages (delete, mark read, restore) | `default_sms_package` (may be null) |
| `not_found` | no such message, thread, contact, part, send, export, restore | |
| `too_many_recipients` | more than `limits.max_recipients` | `max` |
| `too_large` | a text over `max_text_chars` or more than `max_parts` parts, a frame over `max_request_json`, a part over `max_blob_out` | `max` |
| `rate_limited` | sending would exceed the send limits | `retry_after_ms` |
| `no_subscription` | `sub_id` is not an active SIM subscription | |
| `busy` | too many requests in flight; a second export or restore at a time | |
| `user_denied` | the phone's user said no (restore) | |
| `cancelled` | an export was cancelled | |
| `internal` | anything else (no details that could carry personal data) | |

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

### `hello`, `ping`, `status`

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
{"id": 9, "method": "sms.send", "params": {"recipients": ["+15555215554"], "text": "Hi", "sub_id": 1, "delivery_report": true}}
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
- **Rate limit** (all sessions together, counted per recipient): at most
  **20** messages in any 60 s and **100** in any hour. A request that would
  go over is refused whole with `rate_limited` and `retry_after_ms`.
  Android has its own limit too (by default about 30 SMS in 30 minutes per
  app) and then asks the phone's user; that shows as a late `sms.sent` or
  as `sms.failed` with `reason: "limit_exceeded"`.

Result: `{"send_id": 3, "recipients": [{"index": 0, "address": "+15555215554", "parts": 1}]}`
(the addresses as normalized). Then, **to this session only**, per
recipient:

- `sms.sent` `{send_id, index, address}` when every part was handed to the
  network;
- `sms.failed` `{send_id, index, address, reason, result_code}` when a part
  could not be: `reason` one of `generic_failure`, `radio_off`, `null_pdu`,
  `no_service`, `limit_exceeded`, `short_code_not_allowed`,
  `short_code_never_allowed`, `fdn_check_failure`, `other`; `result_code`
  Android's number;
- `sms.delivered` `{send_id, index, address, status}` when the delivery
  reports of all parts are in (`status`: `complete`, `pending`, `failed`);
  only with `delivery_report` and only if the network sends reports.

When the companion is not the default SMS app, Android itself stores sent
messages in the "sent" box; when it is, the companion stores them.

### `sms.status`

`{send_id}` → `{"send_id": 3, "recipients": [{"index": 0, "address": "...", "state": "sent", "reason": null}]}`;
`state` one of `sending`, `sent`, `failed`, `delivered`,
`delivery_failed`. Sends are remembered for an hour (for a client that
reconnected and missed events); older: `not_found`.

### `mms.parts` (needs READ_SMS)

`{id}` (the MMS id) → `{"message_id": 77, "parts": [{"part_id": 301, "mime": "image/jpeg", "name": "IMG_1.jpg", "content_id": "<img1>", "charset": 106, "text": null, "has_data": true}]}`.
`text` is the part's text for `text/plain` and `application/smil`, else null.

### `mms.part_data` (needs READ_SMS)

`{part_id}` → a blob, then `{"part_id": 301, "data": {"blob": 2, "size": 48211, "mime": "image/jpeg"}}`.
Over 64 MiB: `too_large`.

### `mms.send`

Not in version 1 (`not_implemented`). Building an M-Send.req PDU and
handing it to `SmsManager.sendMultimediaMessage` is planned; the request
will take its attachments as client blobs (section 2).

### `subscribe`, `unsubscribe`

`{events: ["message.received", "messages.changed"]}` → `{"events": [...now subscribed...]}`.
`unsubscribe` with `[events]` (default: all). Unknown event names are
`bad_request`.

### `backup.export` (needs READ_SMS)

`{[thread_id], [include_mms: true], [include_mms_data: true]}` →
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
`{}`. The data are streamed as they are read: a slow client slows the
export, nothing piles up on the phone.

**The backup file** (what axross writes; the companion only streams): JSON
Lines, UTF-8. Line 1:
`{"format": "axross-sms-backup", "version": 1, "created": <ms>, "device": {...hello's android...}, "app_version": "0.1.0"}`;
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
   version 1) → `{"inserted": n, "skipped": n, "rejected": n}`. `skipped`:
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
| `sms.sent`, `sms.failed`, `sms.delivered` | the session that sent | see `sms.send` |
| `backup.items`, `backup.done`, `backup.failed` | the exporting session | see `backup.export` |
| `backup.restore.state` | the restoring session | see restore |
| `session.closing` | one session | `{"reason": "..."}` - the last frame before the companion closes |

`session.closing` reasons: `service_stopping`, `fgs_timeout` (Android 15+
ended the service's daily time), `protocol_error`, `slow_reader` (the client
did not read for 60 s while frames waited), `too_many_sessions`,
`handshake_timeout`.

`message.received` comes from a content observer on the SMS/MMS store
(started by `SMS_RECEIVED` too), so it works whether or not the companion
is the default SMS app; it reports messages that arrived while the session
was subscribed, each once.

## 9. Install and start (what axross does over ADB)

| step | command |
|---|---|
| install | `adb install -r axross-android-companion-<v>.apk` (checksum checked first; an install by ADB allows SMS permissions to be granted) |
| grant | `adb shell pm grant io.github.c0decave.axross.companion android.permission.<P>` for READ_SMS, SEND_SMS, RECEIVE_SMS, READ_CONTACTS, READ_PHONE_STATE, POST_NOTIFICATIONS (13+) |
| start | `adb shell am start-foreground-service -n io.github.c0decave.axross.companion/.CompanionService` (Android 8+); the service is exported only to holders of `android.permission.DUMP`, which the shell has and apps do not |
| connect | `adb forward tcp:0 localabstract:axross-companion`, then TCP to the port adb printed |
| open the app | `adb shell am start -n io.github.c0decave.axross.companion/.MainActivity` (when a grant failed: the user grants there) |

Debug builds (CI) are `io.github.c0decave.axross.companion.debug` and
listen on the same socket name; install one or the other. Their classes keep
the release's names, so the component is
`io.github.c0decave.axross.companion.debug/io.github.c0decave.axross.companion.CompanionService`.

Where Android refuses the service's start from the shell, `am start -n
<package>/io.github.c0decave.axross.companion.MainActivity --ez listen true`
starts it from the app's screen.

Which permissions need the phone's user (see README, "Permissions"):
all six can be granted by `pm grant` when the app was installed by ADB;
some vendor systems (e.g. MIUI's "USB debugging (security settings)")
refuse `pm grant`, and then the user grants them in the app. Becoming the
default SMS app (restore, delete, mark read) always needs the user's tap on
the phone.

## 10. Versioning

`protocol` is an integer. Additions that old clients can ignore (new
fields, new methods, new events, new error codes, new capabilities) keep
the version; anything else raises it. A client should treat an unknown
error code as `internal` and an unknown `box`/`status`/`reason` as `other`.
