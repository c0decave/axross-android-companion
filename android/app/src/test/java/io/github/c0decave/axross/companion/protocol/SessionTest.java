package io.github.c0decave.axross.companion.protocol;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class SessionTest {
    /** A handler that answers every request with {"method": ...}, or waits on a latch. */
    static class Echo implements Session.Handler {
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);
        volatile boolean hold;
        volatile Session.Call lastCall;

        @Override
        public Map<String, Object> hello(Session session, Requests.Hello hello) {
            return Json.obj("app", Protocol.APP);
        }

        @Override
        public void handle(Session session, Session.Call call) throws Exception {
            lastCall = call;
            if (hold) {
                release.await(10, TimeUnit.SECONDS);
            }
            if ("sms.status".equals(call.method)) {
                throw new IllegalStateException("a bug with a +15555215554 in it");
            }
            if ("mms.part_data".equals(call.method)) {
                byte[] data = new byte[Frame.CHUNK_DATA + 3];
                data[Frame.CHUNK_DATA + 2] = 42;
                call.okWithBlob(data, "image/png", "data", Json.obj("part_id", call.value));
                return;
            }
            if ("message.get".equals(call.method)) {
                return; // (no answer: the session answers "internal")
            }
            call.ok(Json.obj("method", call.method));
        }

        @Override
        public void closed(Session session) {
            closed.countDown();
        }
    }

    private static Map<String, Object> req(long id, String method, Map<String, Object> params) {
        return Json.obj("id", id, "method", method, "params", params);
    }

    @SuppressWarnings("unchecked")
    private static String code(Map<String, Object> response) {
        return (String) ((Map<String, Object>) response.get("error")).get("code");
    }

    private static Map<String, Object> auth(long id, String proof) {
        return req(id, "auth", Json.obj("proof", proof));
    }

    @SuppressWarnings("unchecked")
    private static String companionNonce(Map<String, Object> helloResponse) {
        Map<String, Object> result = (Map<String, Object>) helloResponse.get("result");
        return (String) ((Map<String, Object>) result.get("auth")).get("companion_nonce");
    }

    @Test
    public void helloAuthThenRequests() throws Exception {
        try (Wire w = new Wire()) {
            Echo h = new Echo();
            w.start(h);
            w.send(Wire.hello());
            Map<String, Object> r = w.nextJson();
            assertEquals(1L, r.get("id"));
            assertEquals(true, r.get("ok"));
            Map<?, ?> result = (Map<?, ?>) r.get("result");
            assertEquals(2L, result.get("protocol"));
            assertEquals(Protocol.APP, result.get("app"));
            String nonce = companionNonce(r);
            assertTrue(Tokens.isHex(nonce, 64));
            assertEquals(Tokens.companionProof(Wire.TOKEN, Wire.CLIENT_NONCE, nonce), ((Map<?, ?>) result.get("auth")).get("proof"));
            assertTrue(w.session.helloDone());
            assertFalse(w.session.authenticated());

            w.send(auth(2, Tokens.clientProof(Wire.TOKEN, nonce, Wire.CLIENT_NONCE)));
            r = w.nextJson();
            assertEquals(2L, r.get("id"));
            assertEquals(true, r.get("ok"));
            assertEquals(Json.obj(), r.get("result"));
            assertTrue(w.session.authenticated());

            w.send(req(3, "threads.list", Json.obj()));
            r = w.nextJson();
            assertEquals(3L, r.get("id"));
            assertEquals("threads.list", ((Map<?, ?>) r.get("result")).get("method"));
        }
    }

    @Test
    public void unknownTokenCloses() throws Exception {
        try (Wire w = new Wire()) {
            Echo h = new Echo();
            w.start(h);
            w.send(req(1, "hello", Json.obj("protocols", Arrays.asList(2L), "client_nonce", Wire.CLIENT_NONCE,
                    "token_id", Tokens.id("ff112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"))));
            Map<String, Object> r = w.nextJson();
            assertEquals("unknown_token", code(r));
            assertFalse(((Map<?, ?>) r.get("error")).containsKey("data"));
            assertTrue(w.closedByCompanion());
            assertTrue(h.closed.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void wrongProofCloses() throws Exception {
        try (Wire w = new Wire()) {
            w.start(new Echo());
            w.send(Wire.hello());
            String nonce = companionNonce(w.nextJson());
            // (the proof of the other direction: the companion's own)
            w.send(auth(2, Tokens.companionProof(Wire.TOKEN, Wire.CLIENT_NONCE, nonce)));
            Map<String, Object> r = w.nextJson();
            assertEquals(2L, r.get("id"));
            assertEquals("auth_failed", code(r));
            assertTrue(w.closedByCompanion());
            assertFalse(w.session.authenticated());
        }
    }

    @Test
    public void aProofIsGoodForOneSessionOnly() throws Exception {
        String proof;
        try (Wire w = new Wire()) {
            w.start(new Echo());
            w.send(Wire.hello());
            proof = Tokens.clientProof(Wire.TOKEN, companionNonce(w.nextJson()), Wire.CLIENT_NONCE);
            w.send(auth(2, proof));
            assertEquals(true, w.nextJson().get("ok"));
        }
        try (Wire w = new Wire()) {
            w.start(new Echo());
            // (the same client nonce, a fresh companion nonce)
            w.send(Wire.hello());
            w.nextJson();
            w.send(auth(2, proof));
            assertEquals("auth_failed", code(w.nextJson()));
            assertTrue(w.closedByCompanion());
        }
    }

    @Test
    public void authOutOfOrder() throws Exception {
        try (Wire w = new Wire()) {
            w.start(new Echo());
            // auth before hello: bad_request, the session goes on
            w.send(auth(1, Wire.TOKEN));
            Map<String, Object> r = w.nextJson();
            assertEquals("bad_request", code(r));
            // a malformed proof: bad_request, the session goes on
            w.send(Wire.hello());
            String nonce = companionNonce(w.nextJson());
            w.send(auth(2, "ABC"));
            r = w.nextJson();
            assertEquals("bad_request", code(r));
            assertEquals("proof", ((Map<?, ?>) ((Map<?, ?>) r.get("error")).get("data")).get("field"));
            w.send(auth(3, Tokens.clientProof(Wire.TOKEN, nonce, Wire.CLIENT_NONCE)));
            assertEquals(true, w.nextJson().get("ok"));
            // a second auth, a second hello: bad_request
            w.send(auth(4, Tokens.clientProof(Wire.TOKEN, nonce, Wire.CLIENT_NONCE)));
            assertEquals("bad_request", code(w.nextJson()));
            w.send(Wire.hello());
            assertEquals("bad_request", code(w.nextJson()));
            w.send(req(5, "ping", Json.obj()));
            assertEquals(5L, w.nextJson().get("id"));
        }
    }

    @Test
    public void aRequestBetweenHelloAndAuthCloses() throws Exception {
        try (Wire w = new Wire()) {
            w.start(new Echo());
            w.send(Wire.hello());
            w.nextJson();
            w.send(req(2, "ping", Json.obj()));
            Map<String, Object> r = w.nextJson();
            assertEquals(2L, r.get("id"));
            assertEquals("handshake_required", code(r));
            assertTrue(w.closedByCompanion());
        }
    }

    @Test
    public void helloChecks() throws Exception {
        try (Wire w = new Wire()) {
            w.start(new Echo());
            // without a nonce: bad_request, the session goes on
            w.send(req(1, "hello", Json.obj("protocols", Arrays.asList(2L), "token_id", Tokens.id(Wire.TOKEN))));
            Map<String, Object> r = w.nextJson();
            assertEquals("bad_request", code(r));
            assertEquals("client_nonce", ((Map<?, ?>) ((Map<?, ?>) r.get("error")).get("data")).get("field"));
            w.handshake();
        }
        try (Wire w = new Wire()) {
            // a version-1 client: unsupported_protocol, closed
            w.start(new Echo());
            w.send(req(1, "hello", Json.obj("protocols", Arrays.asList(1L), "client", "axross")));
            Map<String, Object> r = w.nextJson();
            assertEquals("unsupported_protocol", code(r));
            assertEquals(Arrays.asList(2L), ((Map<?, ?>) ((Map<?, ?>) r.get("error")).get("data")).get("supported"));
            assertTrue(w.closedByCompanion());
        }
    }

    @Test
    public void handlerErrorsAreInternalWithoutDetails() throws Exception {
        try (Wire w = new Wire()) {
            w.start(new Echo());
            w.handshake();
            w.send(req(2, "sms.status", Json.obj("send_id", 1L)));
            Map<String, Object> r = w.nextJson();
            assertEquals("internal", code(r));
            assertTrue(!Json.write(r).contains("5555"));
            w.send(req(3, "message.get", Json.obj("kind", "sms", "id", 1L)));
            assertEquals("internal", code(w.nextJson()));
        }
    }

    @Test
    public void blobBeforeItsAnswer() throws Exception {
        try (Wire w = new Wire()) {
            w.start(new Echo());
            w.handshake();
            w.send(req(2, "mms.part_data", Json.obj("part_id", 301L)));
            Frame a = w.next();
            Frame b = w.next();
            assertEquals(Frame.KIND_BLOB, a.kind);
            assertEquals(0, a.blobFlags());
            assertEquals(Frame.CHUNK_DATA + Frame.BLOB_HEADER, a.length);
            assertEquals(Frame.FLAG_LAST, b.blobFlags());
            assertEquals(a.blobId(), b.blobId());
            assertEquals(42, b.payload[Frame.BLOB_HEADER + 2]);
            Map<String, Object> r = w.nextJson();
            Map<?, ?> data = (Map<?, ?>) ((Map<?, ?>) r.get("result")).get("data");
            assertEquals(a.blobId(), data.get("blob"));
            assertEquals((long) Frame.CHUNK_DATA + 3, data.get("size"));
            assertEquals("image/png", data.get("mime"));
        }
    }

    @Test
    public void inFlightLimit() throws Exception {
        try (Wire w = new Wire()) {
            Echo h = new Echo();
            h.hold = true;
            w.start(h);
            w.handshake();
            for (int i = 0; i < Protocol.MAX_IN_FLIGHT + 1; i++) {
                w.send(req(10 + i, "threads.list", Json.obj()));
            }
            Map<String, Object> r = w.nextJson();
            assertEquals(10L + Protocol.MAX_IN_FLIGHT, r.get("id"));
            assertEquals("busy", code(r));
            h.release.countDown();
            List<Object> ids = new ArrayList<>();
            for (int i = 0; i < Protocol.MAX_IN_FLIGHT; i++) {
                ids.add(w.nextJson().get("id"));
            }
            assertEquals(Protocol.MAX_IN_FLIGHT, ids.size());
            // (the slots are free again)
            w.send(req(99, "threads.list", Json.obj()));
            assertEquals(99L, w.nextJson().get("id"));
        }
    }

    @Test
    public void tooLargeJsonIsAnsweredAndTheSessionGoesOn() throws Exception {
        try (Wire w = new Wire()) {
            w.start(new Echo());
            w.handshake();
            StringBuilder big = new StringBuilder("{\"id\":2,\"method\":\"ping\",\"params\":{\"pad\":\"");
            while (big.length() <= Protocol.MAX_REQUEST_JSON) {
                big.append("xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx");
            }
            big.append("\"}}");
            w.sendText(big.toString());
            Map<String, Object> r = w.nextJson();
            assertEquals(Json.NULL, r.get("id"));
            assertEquals("too_large", code(r));
            w.send(req(3, "ping", Json.obj()));
            assertEquals(3L, w.nextJson().get("id"));
        }
    }

    @Test
    public void badIds() throws Exception {
        try (Wire w = new Wire()) {
            w.start(new Echo());
            w.handshake();
            for (String text : new String[] {"{\"id\":0,\"method\":\"ping\"}", "{\"id\":2147483648,\"method\":\"ping\"}",
                    "{\"id\":\"2\",\"method\":\"ping\"}", "{\"id\":1.5,\"method\":\"ping\"}", "[1]"}) {
                w.sendText(text);
                Map<String, Object> r = w.nextJson();
                assertEquals(text, Json.NULL, r.get("id"));
                assertEquals(text, "bad_request", code(r));
            }
            w.sendText("{\"id\":4,\"method\":\"ping\",\"params\":[1]}");
            Map<String, Object> r = w.nextJson();
            assertEquals(4L, r.get("id"));
            assertEquals("bad_request", code(r));
        }
    }

    @Test
    public void handshakeTimeout() throws Exception {
        // (the session's timer is 10 s; this waits for it)
        try (Wire w = new Wire()) {
            Echo h = new Echo();
            w.start(h);
            Map<String, Object> r = w.nextJson();
            assertEquals("session.closing", r.get("event"));
            assertEquals("handshake_timeout", ((Map<?, ?>) r.get("data")).get("reason"));
            assertTrue(w.closedByCompanion());
            assertTrue(h.closed.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void handshakeTimeoutCoversAuth() throws Exception {
        // (hello, but no auth within the 10 s)
        try (Wire w = new Wire()) {
            w.start(new Echo());
            w.send(Wire.hello());
            assertEquals(true, w.nextJson().get("ok"));
            Map<String, Object> r = w.nextJson();
            assertEquals("session.closing", r.get("event"));
            assertEquals("handshake_timeout", ((Map<?, ?>) r.get("data")).get("reason"));
            assertTrue(w.closedByCompanion());
        }
    }

    @Test
    public void closeSendsTheReason() throws Exception {
        try (Wire w = new Wire()) {
            w.start(new Echo());
            w.handshake();
            w.session.close("service_stopping", true);
            Map<String, Object> r = w.nextJson();
            assertEquals("service_stopping", ((Map<?, ?>) r.get("data")).get("reason"));
            assertTrue(w.closedByCompanion());
            assertTrue(!w.session.event("messages.changed", Json.obj()));
        }
    }

    @Test
    public void clientBlobsAreBoundedAndTaken() throws Exception {
        try (Wire w = new Wire()) {
            w.start(new Echo());
            w.handshake();
            byte[] data = {1, 2, 3};
            w.sendRaw(Frame.blobChunk(5, false, data, 0, 2));
            w.sendRaw(Frame.blobChunk(5, true, data, 2, 1));
            w.send(req(2, "ping", Json.obj()));
            w.nextJson();
            assertArrayEquals(data, w.session.takeBlob(5, 3));
            try {
                w.session.takeBlob(5, 3);
                throw new AssertionError("taken twice");
            } catch (RequestException expected) {
                assertEquals("bad_request", expected.code);
            }
            // one blob over the limit breaks the stream
            byte[] big = new byte[Frame.CHUNK_DATA];
            for (int i = 0; i <= Protocol.MAX_BLOB_IN / Frame.CHUNK_DATA; i++) {
                try {
                    w.sendRaw(Frame.blobChunk(6, false, big, 0, big.length));
                } catch (java.io.IOException closed) {
                    break;
                }
            }
            Map<String, Object> r = w.nextJson();
            assertEquals("protocol_error", ((Map<?, ?>) r.get("data")).get("reason"));
        }
    }

    @Test
    public void slowReaderDoesNotBlockLiveEvents() throws Exception {
        // events never block the caller, whatever the client does
        try (Wire w = new Wire()) {
            w.start(new Echo());
            w.handshake();
            long start = System.nanoTime();
            for (int i = 0; i < 1000; i++) {
                w.session.event("messages.changed", Json.obj());
            }
            assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(5));
        }
    }
}
