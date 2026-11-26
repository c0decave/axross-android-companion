# Changelog

## 0.1.0 (unreleased)

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
