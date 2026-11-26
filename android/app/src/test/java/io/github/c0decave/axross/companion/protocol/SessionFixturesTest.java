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
import org.junit.Test;

/**
 * protocol/fixtures/sessions/*.json against the real session: the
 * fixture's "phone" answers stand in for the phone's data, everything else
 * (framing, handshake, checks, ids, blobs, events, closing) is this code.
 * The service is given the fixture's token; the client's auth proof ("*")
 * and the check of the companion's proof are computed from it. sms.send's
 * client_ref goes through the real {@link SendTracker}. The same files
 * drive axross's fake companion (protocol/README.md).
 */
public class SessionFixturesTest {
    /** Answers from the fixture's "phone" section, in order per method. */
    static final class Phone implements Session.Handler {
        final Map<String, Object> hello;
        final Map<String, ArrayDeque<Map<String, Object>>> answers = new HashMap<>();
        final Map<Long, Object> results = new ConcurrentHashMap<>();
        /** The sends, by client_ref (as Sender keeps them). */
        final SendTracker sends = new SendTracker(System::currentTimeMillis);
        final RateLimiter limiter = new RateLimiter(Protocol.SENDS_PER_MINUTE, Protocol.SENDS_PER_HOUR, System::currentTimeMillis);

        @SuppressWarnings("unchecked")
        Phone(Map<String, Object> phone) {
            hello = (Map<String, Object>) phone.get("hello");
            Map<String, Object> a = (Map<String, Object>) phone.get("answers");
            for (Map.Entry<String, Object> e : a.entrySet()) {
                ArrayDeque<Map<String, Object>> q = new ArrayDeque<>();
                for (Object o : (List<Object>) e.getValue()) {
                    q.add((Map<String, Object>) o);
                }
                answers.put(e.getKey(), q);
            }
        }

        @Override
        public Map<String, Object> hello(Session session, Requests.Hello h) {
            return new HashMap<>(hello);
        }

        @Override
        @SuppressWarnings("unchecked")
        public void handle(Session session, Session.Call call) throws Exception {
            boolean send = "sms.send".equals(call.method);
            if (send) {
                // (a client_ref accepted before: answered, no phone answer taken, no events)
                Map<String, Object> earlier = sends.duplicate(((Requests.SmsSend) call.value).clientRef);
                if (earlier != null) {
                    results.put(call.id, earlier);
                    call.ok(earlier);
                    return;
                }
            }
            ArrayDeque<Map<String, Object>> q = answers.get(call.method);
            Map<String, Object> answer;
            synchronized (this) {
                answer = q == null ? null : q.poll();
            }
            assertNotNull("no phone answer for " + call.method, answer);
            if (answer.containsKey("error")) {
                Map<String, Object> err = (Map<String, Object>) answer.get("error");
                throw new RequestException((String) err.get("code"), (String) err.get("message"),
                        (Map<String, Object>) err.get("data"));
            }
            Map<String, Object> result = new java.util.LinkedHashMap<>((Map<String, Object>) answer.get("result"));
            results.put(call.id, answer.get("result"));
            if (send) {
                // (the phone's result is what the tracker makes of the send)
                Requests.SmsSend s = (Requests.SmsSend) call.value;
                List<Integer> parts = new ArrayList<>();
                for (Object r : (List<Object>) result.get("recipients")) {
                    parts.add((int) (long) (Long) ((Map<String, Object>) r).get("parts"));
                }
                assertEquals("sms.send's phone result", sends.accept(session.number, s.clientRef, s.recipients, parts, limiter), result);
            }
            if (answer.containsKey("blob")) {
                Map<String, Object> blob = (Map<String, Object>) answer.get("blob");
                call.okWithBlob(Wire.hex((String) blob.get("hex")), (String) blob.get("mime"), (String) blob.get("key"), result);
            } else {
                call.ok(result);
            }
            List<Object> events = (List<Object>) answer.get("events");
            if (events != null) {
                for (Object o : events) {
                    Map<String, Object> ev = (Map<String, Object>) o;
                    List<byte[]> before = new ArrayList<>();
                    List<Object> blobs = (List<Object>) ev.get("blobs_before");
                    if (blobs != null) {
                        for (Object b : blobs) {
                            session.blob(Wire.hex((String) ((Map<String, Object>) b).get("hex")), "application/octet-stream", before);
                        }
                    }
                    session.event((String) ev.get("event"), (Map<String, Object>) ev.get("data"), before, true);
                }
            }
        }

        @Override
        public void closed(Session session) {
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
        Phone phone = new Phone(phoneDoc);
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
