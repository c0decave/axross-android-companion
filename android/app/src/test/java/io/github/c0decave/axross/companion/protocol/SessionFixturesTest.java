package io.github.c0decave.axross.companion.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/**
 * protocol/fixtures/sessions/*.json against the real session: the
 * fixture's "phone" answers stand in for the phone's data, everything else
 * (framing, handshake, checks, ids, blobs, events, closing) is this code.
 * The service is given the fixture's token; the client's auth proof ("*")
 * and the check of the companion's proof are computed from it. The sends go
 * through the real {@link SendTracker} and {@link SendStore} (in a fresh
 * directory, with the fixture's store id, first send id and sends of
 * earlier runs): sms.send's phone result must be what they make of it, its
 * events are fed to them as Android's reports and must come out as the
 * fixture says, and sms.status is theirs. Exports go through the real
 * {@link Exports} (window, backup.ack). The same files drive axross's fake
 * companion (protocol/README.md).
 */
public class SessionFixturesTest {
    /** The store id of fixtures whose hello names none. */
    static final String STORE = "00000000000000000000000000000000";

    /** Answers from the fixture's "phone" section, in order per method. */
    static final class Phone implements Session.Handler {
        final Map<String, Object> hello;
        final Map<String, ArrayDeque<Map<String, Object>>> answers = new HashMap<>();
        final Map<Long, Object> results = new ConcurrentHashMap<>();
        final SendStore store;
        final SendTracker sends;
        final Exports exports = new Exports();
        /** Request id -> answered (for an answer's "wait_for"). */
        final Map<Long, CountDownLatch> answered = new ConcurrentHashMap<>();

        @SuppressWarnings("unchecked")
        Phone(Map<String, Object> phone, File dir) throws Exception {
            hello = (Map<String, Object>) phone.get("hello");
            Map<String, Object> a = (Map<String, Object>) phone.get("answers");
            for (Map.Entry<String, Object> e : a.entrySet()) {
                ArrayDeque<Map<String, Object>> q = new ArrayDeque<>();
                for (Object o : (List<Object>) e.getValue()) {
                    q.add((Map<String, Object>) o);
                }
                answers.put(e.getKey(), q);
            }
            Map<String, Object> helloSends = (Map<String, Object>) hello.get("sends");
            String storeId = helloSends == null ? STORE : (String) helloSends.get("store");
            Map<String, Object> s = phone.get("sends") instanceof Map ? (Map<String, Object>) phone.get("sends") : Json.obj();
            long first = s.containsKey("next_send_id") ? (Long) s.get("next_send_id") : 1L;
            long now = System.currentTimeMillis();
            store = SendStore.create(dir, storeId, first, System::currentTimeMillis);
            if (s.get("earlier") instanceof List) {
                for (Object o : (List<Object>) s.get("earlier")) {
                    Map<String, Object> e = (Map<String, Object>) o;
                    List<String> addresses = new ArrayList<>();
                    List<Integer> parts = new ArrayList<>();
                    List<String> states = new ArrayList<>();
                    List<String> reasons = new ArrayList<>();
                    for (Object ro : (List<Object>) e.get("recipients")) {
                        Map<String, Object> r = (Map<String, Object>) ro;
                        addresses.add((String) r.get("address"));
                        parts.add((int) (long) (Long) r.get("parts"));
                        states.add((String) r.get("state"));
                        reasons.add(r.get("reason") instanceof String ? (String) r.get("reason") : null);
                    }
                    Object ref = e.get("client_ref");
                    store.seed((Long) e.get("send_id"), ref instanceof String ? (String) ref : null,
                            now - (Long) e.get("age_ms"), addresses, parts, states, reasons);
                }
            }
            // (as after a restart: what the store holds, read again)
            sends = new SendTracker(SendStore.open(dir, System::currentTimeMillis, new java.util.Random()),
                    new RateLimiter(Protocol.SENDS_PER_MINUTE, Protocol.SENDS_PER_HOUR, System::currentTimeMillis));
            assertEquals("the store opened again", storeId, sends.store().id());
        }

        @Override
        public Map<String, Object> hello(Session session, Requests.Hello h) {
            return new HashMap<>(hello);
        }

        @Override
        public void arrived(Session session, Session.Call call) {
            final CountDownLatch latch = new CountDownLatch(1);
            answered.put(call.id, latch);
            call.whenAnswered(latch::countDown);
            if ("sms.send".equals(call.method)) {
                sends.arrive(call, ((Requests.SmsSend) call.value).clientRef);
            }
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> answer(Session.Call call) throws Exception {
            ArrayDeque<Map<String, Object>> q = answers.get(call.method);
            Map<String, Object> answer;
            synchronized (this) {
                answer = q == null ? null : q.poll();
            }
            assertNotNull("no phone answer for " + call.method, answer);
            if (answer.get("wait_for") instanceof Long) {
                // (the phone is slow: this request ends after that one was answered)
                long other = (Long) answer.get("wait_for");
                CountDownLatch latch = null;
                for (int i = 0; i < 100 && (latch = answered.get(other)) == null; i++) {
                    Thread.sleep(50);
                }
                assertNotNull("wait_for " + other + ": no such request", latch);
                assertTrue("wait_for " + other, latch.await(10, TimeUnit.SECONDS));
            }
            if (answer.containsKey("error")) {
                Map<String, Object> err = (Map<String, Object>) answer.get("error");
                throw new RequestException((String) err.get("code"), (String) err.get("message"),
                        (Map<String, Object>) err.get("data"));
            }
            return answer;
        }

        @Override
        @SuppressWarnings("unchecked")
        public void handle(Session session, Session.Call call) throws Exception {
            switch (call.method) {
                case "sms.send":
                    send(session, call);
                    return;
                case "sms.status": {
                    Requests.SendQuery q = (Requests.SendQuery) call.value;
                    Map<String, Object> st = q.clientRef != null ? sends.status(q.clientRef) : sends.status(q.sendId);
                    if (st == null) {
                        throw new RequestException(ErrorCode.NOT_FOUND, "no such send");
                    }
                    call.ok(st);
                    return;
                }
                case "backup.export":
                    export(session, call);
                    return;
                case "backup.ack": {
                    Requests.Ack a = (Requests.Ack) call.value;
                    exports.ack(session, a.exportId, a.seq, () -> call.ok(Json.obj()));
                    return;
                }
                case "backup.cancel":
                    exports.cancel(session, (Long) call.value, () -> call.ok(Json.obj()));
                    return;
                default:
                    break;
            }
            Map<String, Object> answer = answer(call);
            Map<String, Object> result = new java.util.LinkedHashMap<>((Map<String, Object>) answer.get("result"));
            results.put(call.id, answer.get("result"));
            if (answer.containsKey("blob")) {
                Map<String, Object> blob = (Map<String, Object>) answer.get("blob");
                call.okWithBlob(Wire.hex((String) blob.get("hex")), (String) blob.get("mime"), (String) blob.get("key"), result);
            } else {
                call.ok(result);
            }
            events(session, answer, null);
        }

        /** As Sender: a known client_ref is a duplicate; else the phone's checks, then the tracker. */
        @SuppressWarnings("unchecked")
        private void send(Session session, Session.Call call) throws Exception {
            Requests.SmsSend s = (Requests.SmsSend) call.value;
            Map<String, Object> earlier = sends.resolve(call, s.clientRef);
            if (earlier != null) {
                results.put(call.id, earlier);
                call.ok(earlier);
                return;
            }
            Map<String, Object> answer = answer(call);
            Map<String, Object> result = (Map<String, Object>) answer.get("result");
            List<Integer> parts = new ArrayList<>();
            for (Object r : (List<Object>) result.get("recipients")) {
                parts.add((int) (long) (Long) ((Map<String, Object>) r).get("parts"));
            }
            Map<String, Object> got = sends.accept(call, s.clientRef, session.number, s.recipients, parts);
            assertEquals("sms.send's phone result", result, got);
            results.put(call.id, result);
            call.ok(got);
            // (the events: Android's reports, part by part, through the tracker)
            for (Object o : answer.containsKey("events") ? (List<Object>) answer.get("events") : new ArrayList<>()) {
                Map<String, Object> ev = (Map<String, Object>) o;
                Map<String, Object> d = (Map<String, Object>) ev.get("data");
                long sendId = (Long) d.get("send_id");
                int index = (int) (long) (Long) d.get("index");
                int n = parts.get(index);
                SendTracker.Event out = null;
                String storeId = sends.store().id();
                for (int p = 0; p < n && out == null; p++) {
                    switch ((String) ev.get("event")) {
                        case "sms.sent":
                            out = sends.onSent(storeId, sendId, index, p, true, -1);
                            break;
                        case "sms.failed":
                            out = sends.onSent(storeId, sendId, index, p, false, (int) (long) (Long) d.get("result_code"));
                            break;
                        case "sms.delivered":
                            out = sends.onDelivered(storeId, sendId, index, p, (String) d.get("status"));
                            break;
                        default:
                            fail("not a send event: " + ev.get("event"));
                    }
                }
                assertNotNull("no event from the tracker for " + Json.write(ev), out);
                assertEquals(ev.get("event"), out.name);
                assertEquals("the event's data", d, out.data);
                assertEquals(session.number, out.sessionId);
                session.event(out.name, out.data);
            }
        }

        /** As Exporter: the answer, then the events; backup.items within the window. */
        @SuppressWarnings("unchecked")
        private void export(Session session, Session.Call call) throws Exception {
            Requests.Export req = (Requests.Export) call.value;
            Exports.Export export = exports.start(session, req.window);
            try {
                Map<String, Object> answer = answer(call);
                Map<String, Object> result = (Map<String, Object>) answer.get("result");
                assertEquals("export_id", result.get("export_id"), export.id);
                results.put(call.id, result);
                call.ok(new java.util.LinkedHashMap<>(result));
                events(session, answer, export);
            } finally {
                exports.finish(export);
            }
        }

        @SuppressWarnings("unchecked")
        private void events(Session session, Map<String, Object> answer, Exports.Export export) {
            List<Object> events = (List<Object>) answer.get("events");
            if (events == null) {
                return;
            }
            for (Object o : events) {
                Map<String, Object> ev = (Map<String, Object>) o;
                List<byte[]> before = new ArrayList<>();
                List<Object> blobs = (List<Object>) ev.get("blobs_before");
                if (blobs != null) {
                    for (Object b : blobs) {
                        session.blob(Wire.hex((String) ((Map<String, Object>) b).get("hex")), "application/octet-stream", before);
                    }
                }
                Map<String, Object> data = (Map<String, Object>) ev.get("data");
                if (export != null && "backup.items".equals(ev.get("event"))) {
                    long seq = export.next(session);
                    if (seq < 0) {
                        return;
                    }
                    assertEquals("backup.items' seq", data.get("seq"), seq);
                }
                session.event((String) ev.get("event"), data, before, true);
            }
        }

        @Override
        public void closed(Session session) {
            exports.closed(session);
        }
    }

    @Test
    public void allSessions() throws Exception {
        File dir = new File(Wire.protocolDir(), "fixtures/sessions");
        File[] files = dir.listFiles();
        assertNotNull("no " + dir, files);
        Arrays.sort(files);
        assertTrue(files.length >= 8);
        for (File f : files) {
            if (f.getName().endsWith(".json")) {
                run("fixtures/sessions/" + f.getName());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void run(String path) throws Exception {
        Map<String, Object> doc = Wire.fixture(path);
        Map<String, Object> phoneDoc = (Map<String, Object>) doc.get("phone");
        String token = (String) phoneDoc.get("token");
        assertTrue(path + ": phone.token", Tokens.isHex(token, 64));
        File dir = java.nio.file.Files.createTempDirectory("sends").toFile();
        Phone phone = new Phone(phoneDoc, dir);
        String clientNonce = null;
        String companionNonce = null;
        try (Wire w = new Wire(token)) {
            Session session = w.start(phone);
            int n = 0;
            for (Object o : (List<Object>) doc.get("steps")) {
                Map<String, Object> step = (Map<String, Object>) o;
                String where = path + " step " + n++;
                if (step.containsKey("client")) {
                    Map<String, Object> frame = new java.util.LinkedHashMap<>((Map<String, Object>) step.get("client"));
                    Map<String, Object> params = frame.get("params") instanceof Map
                            ? new java.util.LinkedHashMap<>((Map<String, Object>) frame.get("params")) : null;
                    if ("hello".equals(frame.get("method")) && params != null) {
                        clientNonce = (String) params.get("client_nonce");
                    }
                    if ("auth".equals(frame.get("method")) && params != null && "*".equals(params.get("proof"))) {
                        assertNotNull(where + ": no companion_nonce before auth", companionNonce);
                        params.put("proof", Tokens.clientProof(token, companionNonce, clientNonce));
                        frame.put("params", params);
                    }
                    w.send(frame);
                } else if (step.containsKey("client_text")) {
                    w.sendText((String) step.get("client_text"));
                } else if (step.containsKey("client_hex")) {
                    w.sendRaw(Wire.hex((String) step.get("client_hex")));
                } else if (step.containsKey("client_blob")) {
                    Map<String, Object> b = (Map<String, Object>) step.get("client_blob");
                    byte[] data = Wire.hex((String) b.get("hex"));
                    w.sendRaw(Frame.blobChunk((Long) b.get("blob"), true, data, 0, data.length));
                } else if (step.containsKey("phone_event")) {
                    Map<String, Object> ev = (Map<String, Object>) step.get("phone_event");
                    String name = (String) ev.get("event");
                    if (session.subscribedTo(name)) {
                        session.event(name, (Map<String, Object>) ev.get("data"));
                    }
                } else if (step.containsKey("companion")) {
                    Map<String, Object> got = w.nextJson();
                    match(where, step.get("companion"), got, phone);
                    Object result = got.get("result");
                    if (result instanceof Map && ((Map<String, Object>) result).get("auth") instanceof Map) {
                        // (hello's answer: the companion's proof, from the token)
                        Map<String, Object> auth = (Map<String, Object>) ((Map<String, Object>) result).get("auth");
                        companionNonce = (String) auth.get("companion_nonce");
                        assertTrue(where + ": companion_nonce", Tokens.isHex(companionNonce, 64));
                        assertEquals(where + ": the companion's proof",
                                Tokens.companionProof(token, clientNonce, companionNonce), auth.get("proof"));
                    }
                } else if (step.containsKey("companion_blob")) {
                    Map<String, Object> b = (Map<String, Object>) step.get("companion_blob");
                    ByteArrayOutputStream data = new ByteArrayOutputStream();
                    while (true) {
                        Frame fr = w.next();
                        assertEquals(where + ": a blob chunk", Frame.KIND_BLOB, fr.kind);
                        assertEquals(where, b.get("blob"), fr.blobId());
                        data.write(fr.payload, Frame.BLOB_HEADER, fr.length - Frame.BLOB_HEADER);
                        if ((fr.blobFlags() & Frame.FLAG_LAST) != 0) {
                            break;
                        }
                    }
                    assertTrue(where, Arrays.equals(Wire.hex((String) b.get("hex")), data.toByteArray()));
                } else if (step.containsKey("companion_closes")) {
                    assertTrue(where + ": the companion closes", w.closedByCompanion());
                } else {
                    fail(where + ": unknown step " + step.keySet());
                }
            }
        }
    }

    /** Expected matches actual: objects by their expected keys, "*" anything, "=phone" the phone's result. */
    @SuppressWarnings("unchecked")
    static void match(String where, Object expected, Object actual, Phone phone) {
        if ("*".equals(expected)) {
            return;
        }
        if (expected instanceof Map) {
            assertTrue(where + ": an object, got " + actual, actual instanceof Map);
            Map<String, Object> e = (Map<String, Object>) expected;
            Map<String, Object> a = (Map<String, Object>) actual;
            for (Map.Entry<String, Object> entry : e.entrySet()) {
                assertTrue(where + ": no " + entry.getKey() + " in " + Json.write(a), a.containsKey(entry.getKey()));
                Object want = entry.getValue();
                if ("=phone".equals(want)) {
                    want = phone.results.get((Long) a.get("id"));
                    assertNotNull(where + ": no phone result", want);
                }
                match(where + "." + entry.getKey(), want, a.get(entry.getKey()), phone);
            }
            return;
        }
        if (expected instanceof List) {
            assertTrue(where + ": a list", actual instanceof List);
            List<Object> e = (List<Object>) expected;
            List<Object> a = (List<Object>) actual;
            assertEquals(where + ": list length", e.size(), a.size());
            for (int i = 0; i < e.size(); i++) {
                match(where + "[" + i + "]", e.get(i), a.get(i), phone);
            }
            return;
        }
        assertEquals(where, expected, actual);
    }
}
