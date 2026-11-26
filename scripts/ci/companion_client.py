#!/usr/bin/env python3
"""A small client of the axross companion protocol, version 2, revision 1 (docs/PROTOCOL.md).

Python 3, standard library only. It is the emulator smoke test's client
and a reference for other clients (the codec is the first fifty lines, the
handshake is Connection.hello).

    TOKEN=$(python3 -c 'import secrets; print(secrets.token_hex(32))')
    adb shell am start-foreground-service -n io.github.c0decave.axross.companion/io.github.c0decave.axross.companion.CompanionService --es token $TOKEN
    adb forward tcp:38999 localabstract:axross-companion
    python3 -I scripts/ci/companion_client.py --port 38999 --token $TOKEN call hello
    python3 -I scripts/ci/companion_client.py --port 38999 --token $TOKEN call threads.list '{"limit": 5}'
    python3 -I scripts/ci/companion_client.py --port 38999 --token $TOKEN smoke --sms-to 15555215554 --out smoke-output
    # (the service stopped and started again, with a new token)
    python3 -I scripts/ci/companion_client.py --port 38999 --token $TOKEN2 smoke-restart --out smoke-output

The token is the one the service was started with (--es token); the
client proves that it holds it, and checks that the companion does. The
smoke test prints counts and codes, never message text, numbers beyond the
one it sends to, the token, nonces or proofs.
"""
import argparse
import hashlib
import hmac
import json
import os
import re
import secrets
import shlex
import socket
import struct
import subprocess
import sys
import time

KIND_JSON = 0x01
KIND_BLOB = 0x02
FLAG_LAST = 0x01
MAX_LENGTH = 16 * 1024 * 1024


class ProtocolError(Exception):
    """The stream is broken (bad length, kind or blob chunk; end inside a frame)."""


class NotTheCompanion(Exception):
    """hello's proof is wrong or missing: something else answers on the socket."""


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


def token_id(token):
    """The token's id: the first 32 hex characters of SHA-256 of its 64 characters."""
    return hashlib.sha256(token.encode("ascii")).hexdigest()[:32]


def proof(token, role, first_nonce, second_nonce):
    """HMAC-SHA256 (key: the token's characters) of "axross-companion-2 <role> <a> <b>"."""
    message = f"axross-companion-2 {role} {first_nonce} {second_nonce}".encode("utf-8")
    return hmac.new(token.encode("utf-8"), message, hashlib.sha256).hexdigest()


def is_hex(value, length):
    return isinstance(value, str) and re.fullmatch(f"[0-9a-f]{{{length}}}", value) is not None


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

    def hello(self, token, client_version="smoke", auth=True):
        """The handshake: hello (its proof checked), then auth (unless auth is False).

        Raises CompanionError (unknown_token, unsupported_protocol, ...) or
        NotTheCompanion (a wrong or missing proof: nothing else is sent).
        """
        client_nonce = secrets.token_hex(32)
        result = self.call("hello", {"protocols": [2], "client": "axross-companion-smoke", "client_version": client_version,
                                     "client_nonce": client_nonce, "token_id": token_id(token)})
        a = result.get("auth")
        companion_nonce = a.get("companion_nonce") if isinstance(a, dict) else None
        got = a.get("proof") if isinstance(a, dict) else None
        if not is_hex(companion_nonce, 64) or not is_hex(got, 64) or \
                not hmac.compare_digest(got, proof(token, "companion", client_nonce, companion_nonce)):
            self.close()
            raise NotTheCompanion("hello's proof is wrong: something else answers on the companion's socket")
        self.hello_result = result
        self.client_proof = proof(token, "client", companion_nonce, client_nonce)
        if auth:
            self.call("auth", {"proof": self.client_proof})
        return result

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

    def export_events(self, conn, exp, window):
        """The export's events to the end; with a window, each backup.items acknowledged (after a ping)."""
        items = 0
        what = "backup.export" + (f" (window {window})" if window else "")
        pinged = False
        try:
            while True:
                ev = conn.wait_event(lambda e: e["event"].startswith("backup.") and e["data"].get("export_id") == exp["export_id"], timeout=60)
                if ev["event"] == "backup.items":
                    items += len(ev["data"]["items"])
                    if window:
                        if not pinged:
                            # (the ack is still out: the companion waits, and answers)
                            self.check("time" in conn.call("ping"), f"{what}: a ping is answered while the export waits for its ack")
                            pinged = True
                        acked = conn.call("backup.ack", {"export_id": exp["export_id"], "seq": ev["data"]["seq"]})
                        if ev["data"]["seq"] == 0:
                            self.check(acked == {}, f"{what}: backup.ack answered {{}}")
                elif ev["event"] == "backup.done":
                    break
                else:
                    self.check(False, f"{what} ({ev['event']} {ev['data'].get('code')})")
                    break
            self.check(items == exp["sms_count"] + exp["mms_count"], f"{what} streamed {items} items")
        except TimeoutError:
            self.check(False, f"{what} ends within 60 s")
        if window:
            self.expect_error(conn, "backup.ack", {"export_id": exp["export_id"], "seq": 1 << 40}, "bad_request")
            self.expect_error(conn, "backup.ack", {"export_id": exp["export_id"] + 1000, "seq": 0}, "not_found")

    def restart(self):
        """After the service (and its process) was stopped and started again: the send store is the same."""
        a = self.args
        path = os.path.join(a.out, "smoke-client.json")
        with open(path) as f:
            before = json.load(f)
        conn = self.connect()
        hello = conn.hello(a.token)
        self.report = before
        self.report["failures"] = []
        self.report["warnings"] = before.get("warnings", [])
        self.check((hello.get("sends") or {}).get("store") == before.get("store"),
                   "after the restart: the same send store id")
        send = before.get("send")
        if not send:
            self.check(False, "after the restart: the first run's send is known")
        else:
            ref, send_id = send["client_ref"], send["send_id"]
            st = conn.call("sms.status", {"client_ref": ref})
            self.check(st.get("send_id") == send_id and st.get("store") == before.get("store"),
                       f"after the restart: sms.status by client_ref (send_id {send_id})")
            state = st["recipients"][0]["state"]
            self.check(state in ("sent", "delivered"), f"after the restart: the state is still {state} (was {send.get('state')})")
            self.check(conn.call("sms.status", {"send_id": send_id}).get("client_ref") == ref, "after the restart: sms.status by send_id")
            again = conn.call("sms.send", {"recipients": [before.get("sms_to", "15555215554")], "text": "not sent again", "client_ref": ref})
            self.check(again.get("duplicate") is True and again.get("send_id") == send_id,
                       "after the restart: the same client_ref is a duplicate, not sent again")
            time.sleep(3)
            self.check(not [e for e in conn.events if e["event"].startswith("sms.")], "after the restart: no events for the duplicate")
        self.expect_error(conn, "sms.status", {"client_ref": "never-" + secrets.token_hex(4)}, "not_found")
        conn.close()
        self.report["restart_failures"] = self.failures
        with open(path, "w") as f:
            json.dump(self.report, f, indent=2)
        print(f"smoke client (restart): {len(self.failures)} failures", flush=True)
        return 1 if self.failures else 0

    def run(self):
        a = self.args
        conn = self.connect()
        hello = conn.hello(a.token)
        self.report["hello"] = {k: hello.get(k) for k in ("protocol", "revision", "app", "app_version", "package", "android",
                                                          "default_sms_app", "permissions", "sends")}
        print("hello: " + json.dumps(self.report["hello"]), flush=True)
        self.check(hello.get("protocol") == 2, "protocol version 2, the companion's proof checked, auth done")
        self.check(hello.get("app") == "axross-android-companion", "the app's name")
        self.check("sms.send" in hello.get("capabilities", []), "sms.send is a capability")
        self.check(hello.get("revision") == 1 and {"sms.refs", "backup.ack"} <= set(hello.get("capabilities", [])),
                   "revision 1, with the capabilities sms.refs and backup.ack")
        sends = hello.get("sends") or {}
        store = sends.get("store")
        self.check(is_hex(store, 32) and sends.get("keep_ms") == 86400000, "hello names the send store (32 hex) and keep_ms")
        self.report["store"] = store
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

        # other sessions: the handshake's refusals, each closed
        def refused(what, code, start):
            other = self.connect()
            try:
                start(other)
                frames = other.wait_closed()
                codes = [f.get("error", {}).get("code") for f in frames]
                self.check(code in codes, f"{what}: {code}, closed (got {codes})")
            finally:
                other.close()

        refused("a request before hello", "handshake_required", lambda c: c.send("threads.list", {}))
        refused("a version-1 hello", "unsupported_protocol",
                lambda c: c.send("hello", {"protocols": [1], "client": "axross-companion-smoke"}))
        refused("hello with a token the service was not given", "unknown_token",
                lambda c: c.send("hello", {"protocols": [2], "client_nonce": secrets.token_hex(32),
                                           "token_id": token_id(secrets.token_hex(32))}))

        def wrong_proof(c):
            c.hello(a.token, auth=False)
            c.send("auth", {"proof": secrets.token_hex(32)})
        refused("auth with a wrong proof", "auth_failed", wrong_proof)

        def no_auth(c):
            c.hello(a.token, auth=False)
            c.send("threads.list", {})
        refused("a request between hello and auth", "handshake_required", no_auth)

        # a broken frame -> session.closing protocol_error, closed
        other = self.connect()
        try:
            other.hello(a.token)
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
        ref = f"smoke-{token}"
        params = {"recipients": [a.sms_to], "text": text, "delivery_report": True, "client_ref": ref}
        sent = conn.call("sms.send", params)
        send_id = sent["send_id"]
        self.report["send"] = {"client_ref": ref, "send_id": send_id}
        self.check(len(sent["recipients"]) == 1 and sent["recipients"][0]["parts"] == 1, f"sms.send accepted (send_id {send_id})")
        self.check(sent.get("duplicate") is False and sent.get("client_ref") == ref, "sms.send: duplicate false, client_ref echoed")
        # the same client_ref again (as after a lost answer): answered, not sent again
        again = conn.call("sms.send", params)
        self.check(again.get("duplicate") is True and again.get("send_id") == send_id,
                   f"the same client_ref again: duplicate true, send_id {again.get('send_id')}")
        try:
            ev = conn.wait_event(lambda e: e["event"] in ("sms.sent", "sms.failed") and e["data"].get("send_id") == send_id, timeout=a.sent_timeout)
            self.report["send_event"] = ev["event"]
            if ev["event"] == "sms.failed":
                self.check(False, f"sms.sent arrives (got sms.failed: {ev['data'].get('reason')}, {ev['data'].get('result_code')})")
            else:
                self.check(True, "sms.sent arrives")
            self.check(ev["data"].get("client_ref") == ref, "the event names the client_ref")
        except TimeoutError:
            self.check(False, f"sms.sent arrives within {a.sent_timeout} s")
        try:
            ev = conn.wait_event(lambda e: e["event"] == "sms.delivered" and e["data"].get("send_id") == send_id, timeout=20)
            print(f"info sms.delivered: {ev['data'].get('status')}", flush=True)
        except TimeoutError:
            self.warn("no sms.delivered within 20 s (the emulator may not send reports)")
        st = conn.call("sms.status", {"send_id": send_id})
        self.check(st["recipients"][0]["state"] in ("sent", "delivered"), f"sms.status says {st['recipients'][0]['state']}")
        st = conn.call("sms.status", {"client_ref": ref})
        self.check(st.get("send_id") == send_id and st.get("client_ref") == ref, "sms.status by client_ref")
        self.check(st.get("store") == store and isinstance(st.get("accepted"), int)
                   and abs(st["accepted"] / 1000 - pong["time"] / 1000) < 600,
                   "sms.status names the store and the time of acceptance")
        self.report["send"]["state"] = st["recipients"][0]["state"]
        self.expect_error(conn, "sms.status", {"client_ref": f"never-{token}"}, "not_found")
        self.expect_error(conn, "sms.status", {"send_id": send_id, "client_ref": ref}, "bad_request")
        again_events = [e for e in conn.events if e["event"] == "sms.sent" and e["data"].get("send_id") == send_id]
        self.check(not again_events, "no second sms.sent for the duplicate")

        # the message to ourselves comes back (the emulator's modem loops it)
        try:
            ev = conn.wait_event(lambda e: e["event"] == "message.received" and e["data"]["message"].get("body") == text, timeout=a.receive_timeout)
            self.check(ev["data"]["message"]["box"] == "inbox", "message.received for the SMS sent to ourselves")
        except TimeoutError:
            # (is it in the store? then the companion missed it; if not, it never came back)
            found = conn.call("messages.search", {"query": token})["messages"]
            boxes = sorted(m.get("box") for m in found if m.get("body") == text and m.get("box") != "sent")
            self.report["loopback_boxes"] = boxes
            self.warn(f"no message.received within {a.receive_timeout} s for the SMS to ourselves "
                      f"(in the store: {boxes or 'nothing'})")

        # an incoming SMS from the emulator's console, while subscribed
        if a.inject:
            itext = f"axross companion smoke inject {token}"
            cmd = [part.replace("{text}", itext) for part in shlex.split(a.inject)]
            done = subprocess.run(cmd, capture_output=True, text=True, timeout=60)
            self.check(done.returncode == 0, f"an incoming SMS injected ({cmd[0]} exit {done.returncode})")
            try:
                ev = conn.wait_event(lambda e: e["event"] == "message.received" and e["data"]["message"].get("body") == itext,
                                     timeout=a.receive_timeout)
                m = ev["data"]["message"]
                self.check(m["box"] == "inbox" and m["kind"] == "sms", "message.received for the injected SMS")
            except TimeoutError:
                found = conn.call("messages.search", {"query": "inject " + token})["messages"]
                self.report["inject_boxes"] = sorted(m.get("box") for m in found)
                self.check(False, f"message.received for the injected SMS within {a.receive_timeout} s "
                                  f"(in the store: {self.report['inject_boxes'] or 'nothing'})")

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
            copies = sum(1 for m in result["messages"] if m.get("body") == text and m.get("box") in ("sent", "outbox", "failed", "queued"))
            self.report["sent_copies"] = copies
            self.check(copies == 1, f"sent once although asked twice ({copies} in the sent box)")
            one = next(m for m in conn.call("messages.list", {"thread_id": found["id"], "limit": 20})["messages"] if m.get("body") == text)
            got = conn.call("message.get", {"kind": one["kind"], "id": one["id"]})["message"]
            self.check(got["id"] == one["id"], "message.get")
        else:
            self.warn("the sent SMS was not found in any thread (Android stores it for a non-default app; maybe late)")

        # an export, streamed
        exp = conn.call("backup.export", {"include_mms": True, "include_mms_data": False})
        self.export_events(conn, exp, None)
        # and one paced by acks (window 1): the session answers while the export waits
        exp = conn.call("backup.export", {"include_mms": True, "include_mms_data": False, "window": 1})
        self.export_events(conn, exp, 1)
        self.report["messages_changed_events"] = sum(1 for e in conn.events if e["event"] == "messages.changed")
        conn.close()

        self.report["failures"] = self.failures
        self.report["warnings"] = self.warnings
        self.report["sms_to"] = a.sms_to
        if a.out:
            os.makedirs(a.out, exist_ok=True)
            with open(os.path.join(a.out, "smoke-client.json"), "w") as f:
                json.dump(self.report, f, indent=2)
        print(f"smoke client: {len(self.failures)} failures, {len(self.warnings)} warnings", flush=True)
        return 1 if self.failures else 0


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--port", type=int, default=38999, help="the local end of adb forward tcp:<port> localabstract:axross-companion")
    parser.add_argument("--token", required=True, help="the token the service was started with (--es token, 64 hex characters)")
    sub = parser.add_subparsers(dest="command", required=True)
    c = sub.add_parser("call", help="hello, then one request; prints the answer and any events")
    c.add_argument("method")
    c.add_argument("params", nargs="?", default="{}")
    s = sub.add_parser("smoke", help="the emulator smoke test")
    s.add_argument("--sms-to", required=True, help="the emulator's own number")
    s.add_argument("--out", default=None)
    s.add_argument("--sent-timeout", type=int, default=90)
    s.add_argument("--receive-timeout", type=int, default=60)
    s.add_argument("--inject", default=None, help="a command that makes an SMS arrive; {text} is replaced by its text")
    s.add_argument("--connect-timeout", type=int, default=60, help="seconds to wait for the companion to listen")
    r = sub.add_parser("smoke-restart", help="after smoke and a restart of the service: the send store is kept")
    r.add_argument("--out", required=True, help="the smoke's output directory (its smoke-client.json)")
    r.add_argument("--connect-timeout", type=int, default=60)
    args = parser.parse_args()
    if not is_hex(args.token, 64):
        parser.error("--token must be 64 lowercase hex characters")

    if args.command in ("smoke", "smoke-restart"):
        deadline = time.monotonic() + args.connect_timeout
        while True:
            try:
                probe = Connection(port=args.port, timeout=10)
                probe.hello(args.token)
                probe.close()
                break
            except NotTheCompanion as e:
                print(f"FAIL {e}")
                return 1
            except (OSError, EOFError, ProtocolError, CompanionError) as e:
                if time.monotonic() > deadline:
                    print(f"FAIL the companion does not answer hello on port {args.port}: {e!r}")
                    return 1
                time.sleep(2)
        return Smoke(args).run() if args.command == "smoke" else Smoke(args).restart()

    conn = Connection(port=args.port)
    try:
        try:
            hello = conn.hello(args.token, "cli")
        except NotTheCompanion as e:
            print(json.dumps({"error": "not_the_companion", "message": str(e)}, indent=2))
            return 3
        except CompanionError as e:
            print(json.dumps({"error": e.code, "data": e.data}, indent=2))
            return 2
        if args.method == "hello":
            print(json.dumps({k: v for k, v in hello.items() if k != "auth"}, indent=2, ensure_ascii=False))
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
