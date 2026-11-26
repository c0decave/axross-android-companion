# The protocol, machine-readable

The definition is [docs/PROTOCOL.md](../docs/PROTOCOL.md). This folder holds
what programs check against it. The companion's JVM unit tests run against
these files (`FrameTest`, `RequestsTest`, `SessionFixturesTest`), and so
does `scripts/ci/check_protocol.py` (Python 3, stdlib only). axross's fake
companion and its client tests can use them too.

| file | what it is |
|---|---|
| `schema/protocol-v1.schema.json` | JSON Schema 2020-12. The root validates any JSON frame. `x-methods.<method>.params` / `.result` and `x-events.<event>` point at the `$defs` for each method and event; `x-error-codes` lists the error codes. Unknown fields are allowed everywhere. |
| `fixtures/frames.json` | Frames as hex bytes. A case with `kind` decodes to exactly one frame (`json` with that object, or `blob` with that id, LAST flag and data); a case with `error` must make a reader give up on the stream. |
| `fixtures/requests.json` | Requests and what the companion's parameter check answers: `"expect": "ok"`, or an error code (with `field` for `bad_request` where it is fixed). Only the checks: an `ok` request can still fail on the phone. |
| `fixtures/sessions/*.json` | Scripted sessions: what the client sends, what the companion answers, given canned phone data. |

## Session fixtures

```json
{
  "name": "...", "description": "...",
  "phone": {
    "hello": { ...hello's result without "protocol"... },
    "answers": {
      "<method>": [ <answer>, <answer>, ... ]
    }
  },
  "steps": [ <step>, ... ]
}
```

`answers` are what the phone side gives for a method that passed the
checks, one per call in order. An answer is `{"result": {...}}` or
`{"error": {"code", "message", "data"}}`, optionally with

- `"blob": {"key", "mime", "hex"}`: the blob is sent first, then the result
  with `result[key] = {"blob": <id>, "size": <n>, "mime": <mime>}`;
- `"events": [{"event", "data", ["blobs_before": [{"blob", "hex"}]]}]`:
  sent after the answer, in order, each event's blobs before it.

Requests that the checks refuse (`fixtures/requests.json`'s errors) never
reach the phone and take no answer from the list.

Steps, in order:

| step | meaning |
|---|---|
| `{"client": {...}}` | the client sends this JSON frame |
| `{"client_text": "..."}` | the client sends a JSON frame with exactly this text (also invalid JSON) |
| `{"client_hex": "..."}` | the client sends these raw bytes |
| `{"client_blob": {"blob", "hex"}}` | the client sends a blob in one chunk (LAST set) |
| `{"phone_event": {"event", "data"}}` | something happens on the phone: the companion sends the event to the session if it is subscribed to it |
| `{"companion": {...}}` | the next frame from the companion is a JSON frame that matches |
| `{"companion_blob": {"blob", "hex"}}` | the next frames are the chunks of this blob, with these bytes in all |
| `{"companion_closes": true}` | the companion closes the connection (no frame before the end, beyond those listed) |

Matching: an expected object matches when each of its keys is in the
actual object with a matching value (more keys in the actual are fine); a
list matches element by element, of the same length; `"*"` matches any
value; `"=phone"` (as a response's `result`) matches the phone answer that
request got. Blob ids in the fixtures are the companion's (from 1, per
session, increasing); a client must not rely on particular ids, only on
the references.

## Use in axross

A fake companion: listen on a TCP port (as `adb forward` gives one), speak
the framing, check requests as `fixtures/requests.json` says, answer from
a session fixture's `phone`. The reference client
[`scripts/ci/companion_client.py`](../scripts/ci/companion_client.py) has a
codec in a few lines that can be copied.
