# Changelog

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
