#!/usr/bin/env python3
"""A small client of the axross companion protocol (docs/PROTOCOL.md).

Python 3, standard library only. It is the emulator smoke test's client
and a reference for other clients (the codec is the first fifty lines).

    adb forward tcp:38999 localabstract:axross-companion
    python3 -I scripts/ci/companion_client.py --port 38999 call hello
    python3 -I scripts/ci/companion_client.py --port 38999 call threads.list '{"limit": 5}'
    python3 -I scripts/ci/companion_client.py --port 38999 smoke --sms-to 15555215554 --out smoke-output

The smoke test prints counts and codes, never message text or numbers
beyond the one it sends to.
"""
import argparse
import json
import os
import secrets
import socket
import struct
import sys
import time

KIND_JSON = 0x01
KIND_BLOB = 0x02
FLAG_LAST = 0x01
MAX_LENGTH = 16 * 1024 * 1024


class ProtocolError(Exception):
    """The stream is broken (bad length, kind or blob chunk; end inside a frame)."""


class CompanionError(Exception):
    """An error answer: .code, .data."""

    def __init__(self, code, message, data=None):
        super().__init__(f"{code}: {message}")
        self.code = code
        self.data = data or {}


def encode_json(obj):
    payload = json.dumps(obj, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    if len(payload) + 1 > MAX_LENGTH:
        raise ValueError("JSON frame too large")
    return struct.pack(">IB", len(payload) + 1, KIND_JSON) + payload


def encode_blob_chunk(blob_id, data, last=True):
    payload = struct.pack(">IB", blob_id, FLAG_LAST if last else 0) + data
    return struct.pack(">IB", len(payload) + 1, KIND_BLOB) + payload


def parse_frame(buf):
    """Parses the frame at the start of buf (bytes or bytearray).

    Returns (frame, consumed), or (None, 0) when buf does not yet hold a
    whole frame. frame: ("json", obj) or ("blob", id, last, data). Raises
    ProtocolError for a broken stream (checked as early as the bytes allow).
    """
    if len(buf) < 4:
        return None, 0
    (length,) = struct.unpack(">I", bytes(buf[:4]))
    if length < 1 or length > MAX_LENGTH:
        raise ProtocolError(f"frame length {length} out of range")
    if len(buf) >= 5 and buf[4] not in (KIND_JSON, KIND_BLOB):
        raise ProtocolError(f"unknown frame kind {buf[4]}")
    if len(buf) < 4 + length:
        return None, 0
    kind, payload = buf[4], bytes(buf[5:4 + length])
    if kind == KIND_JSON:
        return ("json", json.loads(payload.decode("utf-8"))), 4 + length
    if len(payload) < 5:
        raise ProtocolError("blob chunk shorter than its header")
    blob_id, flags = struct.unpack(">IB", payload[:5])
    if blob_id == 0 or flags & ~FLAG_LAST:
        raise ProtocolError("bad blob chunk")
    return ("blob", blob_id, bool(flags & FLAG_LAST), payload[5:]), 4 + length


def parse_frames(data):
    """All frames in data; ProtocolError if it ends inside one."""
    frames, buf = [], bytearray(data)
    while buf:
        frame, used = parse_frame(buf)
        if frame is None:
            raise ProtocolError("end of stream inside a frame")
        frames.append(frame)
        del buf[:used]
    return frames


class Connection:
    """One session over TCP (adb forward). Not thread-safe; one caller at a time."""

    def __init__(self, host="127.0.0.1", port=38999, timeout=30.0):
        self.sock = socket.create_connection((host, port), timeout=timeout)
        self.buf = bytearray()
        self.next_id = 1
        self.events = []  # events received and not yet taken
        self.blobs = {}  # complete blobs from the companion, by id
        self._partial = {}
        self.hello_result = None

    def close(self):
        self.sock.close()

    def send_raw(self, data):
        self.sock.sendall(data)

    def send(self, method, params=None):
        rid = self.next_id
        self.next_id += 1
        frame = {"id": rid, "method": method}
        if params is not None:
            frame["params"] = params
        self.send_raw(encode_json(frame))
        return rid

    def read_frame(self, deadline):
        """The next JSON object (blobs are collected on the way); None at the end."""
        while True:
            frame, used = parse_frame(self.buf)
            if frame is None:
                left = deadline - time.monotonic()
                if left <= 0:
                    raise TimeoutError("no frame in time")
                self.sock.settimeout(left)
                try:
                    chunk = self.sock.recv(1 << 20)
                except socket.timeout:
                    raise TimeoutError("no frame in time") from None
                if not chunk:
                    if self.buf:
                        raise ProtocolError("end of stream inside a frame")
                    return None
                self.buf += chunk
                continue
            del self.buf[:used]
            if frame[0] == "json":
                return frame[1]
            _, blob_id, last, data = frame
            self._partial.setdefault(blob_id, bytearray()).extend(data)
            if last:
                self.blobs[blob_id] = bytes(self._partial.pop(blob_id))

    def blob(self, ref):
        """The bytes of a blob reference {"blob", "size", "mime"} (checked)."""
        data = self.blobs.pop(ref["blob"])
        if len(data) != ref["size"]:
            raise ProtocolError("blob size differs from its reference")
        return data

    def wait_response(self, rid, timeout=30.0):
        deadline = time.monotonic() + timeout
        while True:
            obj = self.read_frame(deadline)
            if obj is None:
                raise EOFError("the companion closed the connection")
            if "event" in obj:
                self.events.append(obj)
                continue
            if obj.get("id") == rid or (obj.get("id") is None and not obj.get("ok", True)):
                if obj.get("ok"):
                    return obj["result"]
                err = obj["error"]
                raise CompanionError(err["code"], err.get("message", ""), err.get("data"))

    def call(self, method, params=None, timeout=30.0):
        return self.wait_response(self.send(method, params), timeout)

    def hello(self, client_version="smoke"):
        self.hello_result = self.call("hello", {"protocols": [1], "client": "axross-companion-smoke", "client_version": client_version})
        return self.hello_result

    def wait_event(self, accept, timeout=60.0):
        """The first event for which accept(event) is true (others are kept)."""
        deadline = time.monotonic() + timeout
        while True:
            for i, ev in enumerate(self.events):
                if accept(ev):
                    return self.events.pop(i)
            obj = self.read_frame(deadline)
            if obj is None:
                raise EOFError("the companion closed the connection")
            if "event" in obj:
                self.events.append(obj)

    def wait_closed(self, timeout=15.0):
        """Reads until the companion closes; returns the frames seen before."""
        deadline = time.monotonic() + timeout
        seen = []
        while True:
            try:
                obj = self.read_frame(deadline)
            except (ConnectionResetError, BrokenPipeError):
                return seen
            if obj is None:
                return seen
            seen.append(obj)


# -- the smoke test ---------------------------------------------------------------------------

class Smoke:
    def __init__(self, args):
        self.args = args
        self.failures = []
        self.warnings = []
        self.report = {}

    def check(self, ok, what):
        print(("ok   " if ok else "FAIL ") + what, flush=True)
        if not ok:
            self.failures.append(what)
        return ok

    def warn(self, what):
        print("warn " + what, flush=True)
        self.warnings.append(what)
        if os.environ.get("GITHUB_ACTIONS"):
            print(f"::warning title=companion smoke::{what}", flush=True)

    def connect(self):
        return Connection(port=self.args.port, timeout=30.0)

    def expect_error(self, conn, method, params, code):
        try:
            conn.call(method, params)
        except CompanionError as e:
            return self.check(e.code == code, f"{method} refused with {code} (got {e.code})")
        return self.check(False, f"{method} refused with {code} (it passed)")

    def run(self):
        a = self.args
        conn = self.connect()
        hello = conn.hello()
        self.report["hello"] = {k: hello.get(k) for k in ("protocol", "app", "app_version", "package", "android", "default_sms_app", "permissions")}
        print("hello: " + json.dumps(self.report["hello"]), flush=True)
        self.check(hello.get("protocol") == 1, "protocol version 1")
        self.check(hello.get("app") == "axross-android-companion", "the app's name")
        self.check("sms.send" in hello.get("capabilities", []), "sms.send is a capability")
        perms = hello.get("permissions", {})
        for p in ("READ_SMS", "SEND_SMS", "RECEIVE_SMS", "READ_CONTACTS", "READ_PHONE_STATE"):
            self.check(perms.get(p) is True, f"{p} granted (pm grant)")

        status = conn.call("status")
        self.check(isinstance(status.get("default_sms_app"), bool), "status")
        pong = conn.call("ping")
        self.check(abs(pong["time"] / 1000 - time.time()) < 3600, "ping (the phone's clock is near ours)")

        subs = conn.call("subscriptions.list")
        self.report["subscriptions"] = len(subs["subscriptions"])
        self.check(isinstance(subs["subscriptions"], list), f"subscriptions.list ({len(subs['subscriptions'])} SIMs)")

        contacts = conn.call("contacts.list", {"limit": 100})
        self.check(isinstance(contacts["contacts"], list), f"contacts.list ({contacts['total']} contacts)")
        threads = conn.call("threads.list", {"limit": 50})
        self.check(isinstance(threads["threads"], list), f"threads.list ({len(threads['threads'])} threads)")
        self.report["threads_before"] = len(threads["threads"])

        # refusals
        self.expect_error(conn, "sms.send", {"recipients": [f"+1555000{i:04d}" for i in range(11)], "text": "x"}, "too_many_recipients")
        self.expect_error(conn, "sms.send", {"recipients": ["Alice"], "text": "x"}, "bad_request")
        self.expect_error(conn, "sms.send", {"recipients": [a.sms_to], "text": ""}, "bad_request")
        self.expect_error(conn, "mms.send", {}, "not_implemented")
        self.expect_error(conn, "no.such.method", {}, "unknown_method")
        if not hello.get("default_sms_app"):
            self.expect_error(conn, "message.delete", {"kind": "sms", "id": 1}, "not_default_sms_app")
            self.expect_error(conn, "message.mark_read", {"thread_id": 1}, "not_default_sms_app")
        self.expect_error(conn, "messages.list", {"thread_id": 0}, "bad_request")

        # a second session: no hello first -> refused and closed
        other = self.connect()
        try:
            other.send("threads.list", {})
            frames = other.wait_closed()
            codes = [f.get("error", {}).get("code") for f in frames]
            self.check("handshake_required" in codes, "a request before hello: handshake_required, closed")
        finally:
            other.close()
        # a broken frame -> session.closing protocol_error, closed
        other = self.connect()
        try:
            other.hello()
            other.send_raw(b"\x00\x00\x00\x00")
            frames = other.wait_closed()
            reasons = [f.get("data", {}).get("reason") for f in frames if f.get("event") == "session.closing"]
            self.check(reasons == ["protocol_error"], "a frame of length 0: session.closing (protocol_error), closed")
        finally:
            other.close()
        # the first session is still fine
        self.check("time" in conn.call("ping"), "the first session is untouched")

        # send to ourselves
        conn.call("subscribe", {"events": ["message.received", "messages.changed"]})
        token = secrets.token_hex(4)
        text = f"axross companion smoke {token}"
        sent = conn.call("sms.send", {"recipients": [a.sms_to], "text": text, "delivery_report": True})
        send_id = sent["send_id"]
        self.check(len(sent["recipients"]) == 1 and sent["recipients"][0]["parts"] == 1, f"sms.send accepted (send_id {send_id})")
        try:
            ev = conn.wait_event(lambda e: e["event"] in ("sms.sent", "sms.failed") and e["data"].get("send_id") == send_id, timeout=a.sent_timeout)
            self.report["send_event"] = ev["event"]
            if ev["event"] == "sms.failed":
                self.check(False, f"sms.sent arrives (got sms.failed: {ev['data'].get('reason')}, {ev['data'].get('result_code')})")
            else:
                self.check(True, "sms.sent arrives")
        except TimeoutError:
            self.check(False, f"sms.sent arrives within {a.sent_timeout} s")
        try:
            ev = conn.wait_event(lambda e: e["event"] == "sms.delivered" and e["data"].get("send_id") == send_id, timeout=20)
            print(f"info sms.delivered: {ev['data'].get('status')}", flush=True)
        except TimeoutError:
            self.warn("no sms.delivered within 20 s (the emulator may not send reports)")
        st = conn.call("sms.status", {"send_id": send_id})
        self.check(st["recipients"][0]["state"] in ("sent", "delivered"), f"sms.status says {st['recipients'][0]['state']}")

        # the message to ourselves comes back (the emulator's modem loops it)
        try:
            ev = conn.wait_event(lambda e: e["event"] == "message.received" and e["data"]["message"].get("body") == text, timeout=a.receive_timeout)
            self.check(ev["data"]["message"]["box"] == "inbox", "message.received for the SMS sent to ourselves")
        except TimeoutError:
            self.warn(f"no message.received within {a.receive_timeout} s (the SMS to ourselves did not come back)")

        # it is in the store: threads, messages, search
        threads = conn.call("threads.list", {"limit": 50})
        self.report["threads_after"] = len(threads["threads"])
        found = None
        for th in threads["threads"]:
            page = conn.call("messages.list", {"thread_id": th["id"], "limit": 20})
            if any(m.get("body") == text for m in page["messages"]):
                found = th
                break
        if found:
            self.check(True, "the sent SMS is in a thread (messages.list)")
            result = conn.call("messages.search", {"query": token})
            self.check(any(m.get("body") == text for m in result["messages"]), "messages.search finds it")
            one = next(m for m in conn.call("messages.list", {"thread_id": found["id"], "limit": 20})["messages"] if m.get("body") == text)
            got = conn.call("message.get", {"kind": one["kind"], "id": one["id"]})["message"]
            self.check(got["id"] == one["id"], "message.get")
        else:
            self.warn("the sent SMS was not found in any thread (Android stores it for a non-default app; maybe late)")

        # an export, streamed
        exp = conn.call("backup.export", {"include_mms": True, "include_mms_data": False})
        items = 0
        try:
            while True:
                ev = conn.wait_event(lambda e: e["event"].startswith("backup.") and e["data"].get("export_id") == exp["export_id"], timeout=60)
                if ev["event"] == "backup.items":
                    items += len(ev["data"]["items"])
                elif ev["event"] == "backup.done":
                    break
                else:
                    self.check(False, f"backup.export ({ev['event']} {ev['data'].get('code')})")
                    break
            self.check(items == exp["sms_count"] + exp["mms_count"], f"backup.export streamed {items} items")
        except TimeoutError:
            self.check(False, "backup.export ends within 60 s")
        conn.close()

        self.report["failures"] = self.failures
        self.report["warnings"] = self.warnings
        if a.out:
            os.makedirs(a.out, exist_ok=True)
            with open(os.path.join(a.out, "smoke-client.json"), "w") as f:
                json.dump(self.report, f, indent=2)
        print(f"smoke client: {len(self.failures)} failures, {len(self.warnings)} warnings", flush=True)
        return 1 if self.failures else 0


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--port", type=int, default=38999, help="the local end of adb forward tcp:<port> localabstract:axross-companion")
    sub = parser.add_subparsers(dest="command", required=True)
    c = sub.add_parser("call", help="hello, then one request; prints the answer and any events")
    c.add_argument("method")
    c.add_argument("params", nargs="?", default="{}")
    s = sub.add_parser("smoke", help="the emulator smoke test")
    s.add_argument("--sms-to", required=True, help="the emulator's own number")
    s.add_argument("--out", default=None)
    s.add_argument("--sent-timeout", type=int, default=90)
    s.add_argument("--receive-timeout", type=int, default=60)
    s.add_argument("--connect-timeout", type=int, default=60, help="seconds to wait for the companion to listen")
    args = parser.parse_args()

    if args.command == "smoke":
        deadline = time.monotonic() + args.connect_timeout
        while True:
            try:
                probe = Connection(port=args.port, timeout=10)
                probe.hello()
                probe.close()
                break
            except (OSError, EOFError, ProtocolError, CompanionError) as e:
                if time.monotonic() > deadline:
                    print(f"FAIL the companion does not answer hello on port {args.port}: {e!r}")
                    return 1
                time.sleep(2)
        return Smoke(args).run()

    conn = Connection(port=args.port)
    try:
        hello = conn.hello("cli")
        if args.method == "hello":
            print(json.dumps(hello, indent=2, ensure_ascii=False))
            return 0
        try:
            result = conn.call(args.method, json.loads(args.params))
            print(json.dumps(result, indent=2, ensure_ascii=False))
        except CompanionError as e:
            print(json.dumps({"error": e.code, "data": e.data}, indent=2))
            return 2
        for ev in conn.events:
            print(json.dumps(ev, ensure_ascii=False))
        return 0
    finally:
        conn.close()


if __name__ == "__main__":
    sys.exit(main())
