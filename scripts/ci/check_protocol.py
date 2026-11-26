#!/usr/bin/env python3
"""The machine-readable protocol checked against itself (CI, before a build).

    python3 -I scripts/ci/check_protocol.py

- protocol/schema/protocol-v1.schema.json is valid JSON whose $refs all
  resolve, with params and result for every method and data for every
  event;
- protocol/fixtures/frames.json decodes as it says (with the reference
  client's codec);
- every 'ok' case of protocol/fixtures/requests.json validates against its
  method's params schema; every error is a known code;
- every step of protocol/fixtures/sessions/*.json validates: client frames
  as requests with their params, companion frames as responses (with the
  result of the method they answer) or events (with their data), the phone
  answers and events likewise;
- docs/PROTOCOL.md names every method, event and error code of the schema.

A small validator for the subset of JSON Schema the file uses (type,
properties, required, items, enum, const, minimum, maximum, minLength,
maxLength, minItems, maxItems, oneOf, anyOf, allOf, not, $ref). Exits 1
with one line per finding.
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(Path(__file__).resolve().parent))
import companion_client  # noqa: E402  (the codec under test)

SCHEMA = json.loads((ROOT / "protocol/schema/protocol-v1.schema.json").read_text(encoding="utf-8"))
problems = []


def resolve(ref):
    if not ref.startswith("#/$defs/"):
        raise ValueError(f"unsupported $ref {ref}")
    return SCHEMA["$defs"][ref[len("#/$defs/"):]]


def type_ok(value, t):
    if isinstance(t, list):
        return any(type_ok(value, x) for x in t)
    return {
        "object": isinstance(value, dict),
        "array": isinstance(value, list),
        "string": isinstance(value, str),
        "integer": isinstance(value, int) and not isinstance(value, bool),
        "number": isinstance(value, (int, float)) and not isinstance(value, bool),
        "boolean": isinstance(value, bool),
        "null": value is None,
    }[t]


def errors(value, schema, path="$"):
    """The ways value fails schema (empty: it validates)."""
    out = []
    if "$ref" in schema:
        out += errors(value, resolve(schema["$ref"]), path)
    if "type" in schema and not type_ok(value, schema["type"]):
        return out + [f"{path}: not {schema['type']}"]
    if "const" in schema and value != schema["const"]:
        out.append(f"{path}: not {schema['const']!r}")
    if "enum" in schema and value not in schema["enum"]:
        out.append(f"{path}: {value!r} not in {schema['enum']}")
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        if "minimum" in schema and value < schema["minimum"]:
            out.append(f"{path}: below {schema['minimum']}")
        if "maximum" in schema and value > schema["maximum"]:
            out.append(f"{path}: above {schema['maximum']}")
    if isinstance(value, str):
        n = len(value.encode("utf-16-le")) // 2
        if n < schema.get("minLength", 0) or n > schema.get("maxLength", 1 << 62):
            out.append(f"{path}: length {n} out of range")
    if isinstance(value, list):
        if len(value) < schema.get("minItems", 0) or len(value) > schema.get("maxItems", 1 << 62):
            out.append(f"{path}: {len(value)} items out of range")
        if "items" in schema:
            for i, item in enumerate(value):
                out += errors(item, schema["items"], f"{path}[{i}]")
    if isinstance(value, dict):
        for key in schema.get("required", []):
            if key not in value:
                out.append(f"{path}: no {key}")
        for key, sub in schema.get("properties", {}).items():
            if key in value:
                out += errors(value[key], sub, f"{path}.{key}")
    if "allOf" in schema:
        for sub in schema["allOf"]:
            out += errors(value, sub, path)
    if "anyOf" in schema and not any(not errors(value, s, path) for s in schema["anyOf"]):
        out.append(f"{path}: matches none of anyOf")
    if "oneOf" in schema:
        n = sum(1 for s in schema["oneOf"] if not errors(value, s, path))
        if n != 1:
            out.append(f"{path}: matches {n} of oneOf")
    if "not" in schema and not errors(value, schema["not"], path):
        out.append(f"{path}: matches 'not'")
    return out


def check(value, schema, where):
    for e in errors(value, schema):
        problems.append(f"{where}: {e}")


def all_refs(node):
    if isinstance(node, dict):
        for k, v in node.items():
            if k == "$ref":
                yield v
            else:
                yield from all_refs(v)
    elif isinstance(node, list):
        for v in node:
            yield from all_refs(v)


def check_schema():
    for ref in all_refs(SCHEMA):
        try:
            resolve(ref)
        except (KeyError, ValueError):
            problems.append(f"schema: $ref {ref} does not resolve")
    for name, m in SCHEMA["x-methods"].items():
        if set(m) != {"params", "result"}:
            problems.append(f"schema: x-methods.{name} needs params and result")


def check_frames():
    doc = json.loads((ROOT / "protocol/fixtures/frames.json").read_text(encoding="utf-8"))
    for case in doc["frames"]:
        where = f"frames.json {case['name']!r}"
        data = bytes.fromhex(case["hex"])
        try:
            frames = companion_client.parse_frames(data)
        except companion_client.ProtocolError:
            if "error" not in case:
                problems.append(f"{where}: does not decode")
            continue
        if "error" in case:
            problems.append(f"{where}: decodes, but should be a protocol error")
            continue
        if len(frames) != 1:
            problems.append(f"{where}: {len(frames)} frames")
            continue
        f = frames[0]
        if case["kind"] == "json":
            if f != ("json", case["json"]):
                problems.append(f"{where}: decodes to {f!r}")
            check(case["json"], SCHEMA, where)
        elif f != ("blob", case["blob"], case["last"], bytes.fromhex(case["data_hex"])):
            problems.append(f"{where}: decodes to {f!r}")


def params_schema(method):
    m = SCHEMA["x-methods"].get(method)
    return m["params"] if m else None


def check_requests():
    doc = json.loads((ROOT / "protocol/fixtures/requests.json").read_text(encoding="utf-8"))
    names = set()
    for case in doc["cases"]:
        where = f"requests.json {case['name']!r}"
        if case["name"] in names:
            problems.append(f"{where}: name used twice")
        names.add(case["name"])
        if case["expect"] == "ok":
            schema = params_schema(case["method"])
            if schema is None:
                problems.append(f"{where}: no such method in the schema")
            else:
                check(case["params"], schema, where)
        elif case["expect"] not in SCHEMA["x-error-codes"]:
            problems.append(f"{where}: unknown error code {case['expect']}")


def check_sessions():
    for path in sorted((ROOT / "protocol/fixtures/sessions").glob("*.json")):
        doc = json.loads(path.read_text(encoding="utf-8"))
        name = path.name
        methods = {}
        answers = {k: list(v) for k, v in doc["phone"]["answers"].items()}
        hello = doc["phone"]["hello"]
        check(dict(hello, protocol=1), SCHEMA["x-methods"]["hello"]["result"], f"{name} phone.hello")
        for method, lst in doc["phone"]["answers"].items():
            if method not in SCHEMA["x-methods"]:
                problems.append(f"{name}: answers for unknown method {method}")
                continue
            for i, ans in enumerate(lst):
                where = f"{name} phone.answers.{method}[{i}]"
                if "result" in ans:
                    result = dict(ans["result"])
                    if "blob" in ans:
                        result[ans["blob"]["key"]] = {"blob": 1, "size": len(bytes.fromhex(ans["blob"]["hex"])), "mime": ans["blob"]["mime"]}
                    check(result, SCHEMA["x-methods"][method]["result"], where)
                else:
                    check({"id": 1, "ok": False, "error": ans["error"]}, SCHEMA, where)
                    if ans["error"]["code"] not in SCHEMA["x-error-codes"]:
                        problems.append(f"{where}: unknown error code")
                for ev in ans.get("events", []):
                    check_event(ev, f"{where} event")
        for i, step in enumerate(doc["steps"]):
            where = f"{name} step {i}"
            if "client" in step:
                frame = step["client"]
                check(frame, SCHEMA["$defs"]["Request"], where)
                methods[frame["id"]] = frame["method"]
            elif "companion" in step:
                frame = step["companion"]
                if "event" in frame:
                    if frame["data"] != "*":
                        check_event(frame, where, partial=True)
                elif frame.get("ok") is False:
                    if frame["error"]["code"] not in SCHEMA["x-error-codes"]:
                        problems.append(f"{where}: unknown error code")
                elif frame.get("ok") is True and frame["result"] not in ("=phone",):
                    method = methods.get(frame["id"])
                    result = {k: v for k, v in frame["result"].items() if v != "*"}
                    if method and not any(v == "*" for v in frame["result"].values()):
                        check(result, SCHEMA["x-methods"][method]["result"], where)
            elif "phone_event" in step:
                check_event(step["phone_event"], where)
            elif not any(k in step for k in ("client_text", "client_hex", "client_blob", "companion_blob", "companion_closes")):
                problems.append(f"{where}: unknown step {sorted(step)}")


def check_event(ev, where, partial=False):
    schema = SCHEMA["x-events"].get(ev["event"])
    if schema is None:
        problems.append(f"{where}: unknown event {ev['event']}")
        return
    if partial and any(v == "*" for v in ev["data"].values()):
        return
    check(ev["data"], schema, where)


def check_doc():
    text = (ROOT / "docs/PROTOCOL.md").read_text(encoding="utf-8")
    for kind, names in (("method", SCHEMA["x-methods"]), ("event", SCHEMA["x-events"]), ("error code", SCHEMA["x-error-codes"])):
        for n in names:
            if not re.search(r"`" + re.escape(n) + r"`", text):
                problems.append(f"docs/PROTOCOL.md: the {kind} {n} is not described")


def main():
    check_schema()
    check_frames()
    check_requests()
    check_sessions()
    check_doc()
    for p in problems:
        print(p)
    if problems:
        return 1
    print("protocol: the schema, the fixtures and docs/PROTOCOL.md agree")
    return 0


if __name__ == "__main__":
    sys.exit(main())
