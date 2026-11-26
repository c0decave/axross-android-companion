package io.github.c0decave.axross.companion.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/**
 * sms.send's client_ref and sms.status over the send store: a send asked
 * for again is answered, not sent again - in the same run, while the first
 * is still checked, and in a later run; "pending" while checked; a refusal
 * leaves nothing behind.
 */
public class SendTrackerTest {
    private static final List<String> ONE = Arrays.asList("+15555215554");
    private static final List<Integer> PARTS = Arrays.asList(1);

    /** A session that is never run: only for its Calls. */
    private static final Session SESSION = new Session(new Session.Transport() {
        @Override
        public InputStream input() {
            throw new UnsupportedOperationException();
        }

        @Override
        public OutputStream output() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {
        }
    }, Protocol.UID_SHELL, new Tokens(), null, null, null, Wire.QUIET);

    private static long ids;

    static Session.Call call(String clientRef) {
        return SESSION.new Call(++ids, "sms.send", clientRef);
    }

    private static SendTracker tracker(File dir, SendStoreTest.Clock clock, int perMinute) {
        return new SendTracker(SendStore.open(dir, clock, new SecureRandom()), new RateLimiter(perMinute, 100, clock));
    }

    /** sms.send as Sender does it, the checks passing. */
    private static Map<String, Object> send(SendTracker t, Session.Call call, String ref) throws RequestException {
        t.arrive(call, ref);
        try {
            Map<String, Object> earlier = t.resolve(call, ref);
            Map<String, Object> result = earlier != null ? earlier : t.accept(call, ref, 4, ONE, PARTS);
            call.ok(result);
            return result;
        } catch (RequestException e) {
            call.fail(e);
            throw e;
        }
    }

    @Test
    public void aClientRefIsSentOnce() throws Exception {
        SendStoreTest.Clock clock = new SendStoreTest.Clock();
        SendTracker t = tracker(SendStoreTest.dir(), clock, 2);
        Map<String, Object> first = send(t, call("ref-1"), "ref-1");
        assertFalse(SendTracker.isDuplicate(first));
        long id = (Long) first.get("send_id");
        assertEquals("ref-1", first.get("client_ref"));
        assertEquals(false, first.get("duplicate"));
        assertEquals(Arrays.asList(Json.obj("index", 0L, "address", "+15555215554", "parts", 1L)), first.get("recipients"));
        for (int i = 0; i < 5; i++) {
            Map<String, Object> again = send(t, call("ref-1"), "ref-1");
            assertTrue(SendTracker.isDuplicate(again));
            assertEquals(id, again.get("send_id"));
            assertEquals(first.get("recipients"), again.get("recipients"));
        }
        // the limit (2 a minute) still has room for one: the duplicates took none
        assertEquals(id + 1, send(t, call("ref-2"), "ref-2").get("send_id"));
        try {
            send(t, call("ref-3"), "ref-3");
            fail("over the limit");
        } catch (RequestException e) {
            assertEquals(ErrorCode.RATE_LIMITED, e.code);
        }
        // a refused send's ref was not accepted: not_found, and it may come again
        assertNull(t.status("ref-3"));
        clock.now += 61_000;
        assertFalse(SendTracker.isDuplicate(send(t, call("ref-3"), "ref-3")));
        // without a ref: never a duplicate, no client_ref in the result
        clock.now += 61_000;
        Map<String, Object> plain = send(t, call(null), null);
        assertFalse(plain.containsKey("client_ref"));
        assertFalse(SendTracker.isDuplicate(plain));
    }

    @Test
    public void pendingWhileChecked() throws Exception {
        SendTracker t = tracker(SendStoreTest.dir(), new SendStoreTest.Clock(), 20);
        Session.Call c = call("p");
        t.arrive(c, "p");
        assertEquals(Json.obj("client_ref", "p", "send_id", null, "recipients", new ArrayList<Object>(),
                "pending", true, "store", t.store().id()), t.status("p"));
        assertNull(t.resolve(c, "p"));
        assertTrue((Boolean) t.status("p").get("pending"));
        // refused (permission, SIM, parts ...): the registration goes with the answer
        c.fail(RequestException.permission("SEND_SMS"));
        assertNull(t.status("p"));

        Session.Call d = call("q");
        t.arrive(d, "q");
        assertNull(t.resolve(d, "q"));
        Map<String, Object> r = t.accept(d, "q", 4, ONE, PARTS);
        Map<String, Object> st = t.status("q");
        assertEquals(r.get("send_id"), st.get("send_id"));
        assertFalse(st.containsKey("pending"));
        assertEquals(t.store().id(), st.get("store"));
        assertTrue(st.get("accepted") instanceof Long);
        assertEquals("sending", ((Map<?, ?>) ((List<?>) st.get("recipients")).get(0)).get("state"));
    }

    @Test
    public void aSecondRequestWaitsForTheFirstsOutcome() throws Exception {
        SendTracker t = tracker(SendStoreTest.dir(), new SendStoreTest.Clock(), 20);
        ExecutorService pool = Executors.newCachedThreadPool();
        try {
            // accepted: the second gets the first's result, after the first's answer
            final Session.Call a = call("x");
            final Session.Call b = call("x");
            t.arrive(a, "x");
            t.arrive(b, "x");
            assertNull(t.resolve(a, "x"));
            Future<Map<String, Object>> second = pool.submit(() -> t.resolve(b, "x"));
            Thread.sleep(200);
            assertFalse(second.isDone());
            Map<String, Object> result = t.accept(a, "x", 4, ONE, PARTS);
            Thread.sleep(200);
            assertFalse("waits for the first's answer", second.isDone());
            a.ok(result);
            Map<String, Object> dup = second.get(5, TimeUnit.SECONDS);
            assertTrue(SendTracker.isDuplicate(dup));
            assertEquals(result.get("send_id"), dup.get("send_id"));

            // refused: the second gets the same refusal
            final Session.Call c = call("y");
            final Session.Call d = call("y");
            t.arrive(c, "y");
            t.arrive(d, "y");
            assertNull(t.resolve(c, "y"));
            Future<Map<String, Object>> other = pool.submit(() -> t.resolve(d, "y"));
            Thread.sleep(200);
            c.fail(new RequestException(ErrorCode.RATE_LIMITED, "too many", Json.obj("retry_after_ms", 41000L)));
            try {
                other.get(5, TimeUnit.SECONDS);
                fail("not refused");
            } catch (java.util.concurrent.ExecutionException e) {
                RequestException r = (RequestException) e.getCause();
                assertEquals(ErrorCode.RATE_LIMITED, r.code);
                assertEquals(41000L, r.data.get("retry_after_ms"));
            }
            assertNull(t.status("y"));
            // the one after that checks again
            Session.Call f = call("y");
            t.arrive(f, "y");
            assertNull(t.resolve(f, "y"));
            assertTrue((Boolean) t.status("y").get("pending"));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void aSendThatCannotBeRecordedIsInternalAndNotAccepted() throws Exception {
        File notADir = File.createTempFile("sends", ".file");
        SendTracker t = tracker(notADir, new SendStoreTest.Clock(), 20);
        try {
            send(t, call("z"), "z");
            fail("accepted");
        } catch (RequestException e) {
            assertEquals(ErrorCode.INTERNAL, e.code);
        }
        assertNull(t.status("z"));
        assertEquals(0, t.store().size());
    }

    @Test
    public void eventsNameTheClientRefAndTheirSession() throws Exception {
        SendTracker t = tracker(SendStoreTest.dir(), new SendStoreTest.Clock(), 20);
        long id = (Long) send(t, call("e"), "e").get("send_id");
        String store = t.store().id();
        // (a PendingIntent of another store - the app's data were cleared since - is ignored)
        assertNull(t.onSent("ffffffffffffffffffffffffffffffff", id, 0, 0, true, -1));
        SendTracker.Event e = t.onSent(store, id, 0, 0, true, -1);
        assertEquals("sms.sent", e.name);
        assertEquals(4L, e.sessionId);
        assertEquals(Json.obj("send_id", id, "index", 0L, "address", "+15555215554", "client_ref", "e"), e.data);
        e = t.onDelivered(store, id, 0, 0, "failed");
        assertEquals(Json.obj("send_id", id, "index", 0L, "address", "+15555215554", "status", "failed", "client_ref", "e"), e.data);
        assertEquals("delivery_failed", ((Map<?, ?>) ((List<?>) t.status(id).get("recipients")).get(0)).get("state"));
    }

    @Test
    public void aLaterRunAnswersFromTheStore() throws Exception {
        SendStoreTest.Clock clock = new SendStoreTest.Clock();
        File dir = SendStoreTest.dir();
        SendTracker t = tracker(dir, clock, 20);
        long id = (Long) send(t, call("r"), "r").get("send_id");
        String store = t.store().id();
        t.onSent(store, id, 0, 0, true, -1);

        // the service (or the process) ends; a new run
        clock.now += 3_600_000L * 5;
        SendTracker u = tracker(dir, clock, 20);
        assertEquals(store, u.store().id());
        Map<String, Object> st = u.status("r");
        assertEquals(id, st.get("send_id"));
        assertEquals(store, st.get("store"));
        assertEquals("sent", ((Map<?, ?>) ((List<?>) st.get("recipients")).get(0)).get("state"));
        assertEquals(st, u.status(id));
        // the same ref again: not sent again
        Map<String, Object> dup = send(u, call("r"), "r");
        assertTrue(SendTracker.isDuplicate(dup));
        assertEquals(id, dup.get("send_id"));
        // a report of the earlier run's send: recorded; no session of this run gets it
        SendTracker.Event e = u.onDelivered(store, id, 0, 0, "complete");
        assertEquals(-1L, e.sessionId);
        assertEquals("delivered", ((Map<?, ?>) ((List<?>) u.status("r").get("recipients")).get(0)).get("state"));
        // and the next send id goes on
        assertEquals(id + 1, send(u, call("s"), "s").get("send_id"));
    }

    @Test
    public void theRateLimitHoldsAcrossRuns() throws Exception {
        SendStoreTest.Clock clock = new SendStoreTest.Clock();
        File dir = SendStoreTest.dir();
        SendTracker t = tracker(dir, clock, 2);
        send(t, call("a"), "a");
        send(t, call("b"), "b");
        SendTracker u = tracker(dir, clock, 2);
        try {
            send(u, call("c"), "c");
            fail("the limit was forgotten");
        } catch (RequestException e) {
            assertEquals(ErrorCode.RATE_LIMITED, e.code);
        }
        clock.now += 61_000;
        assertNotNull(send(tracker(dir, clock, 2), call("c"), "c"));
    }

    @Test
    public void requestCodesAreUniquePerSendRecipientAndPart() {
        Set<Integer> codes = new HashSet<>();
        long base = 2_000_000_000L;
        for (long id = base; id < base + 300; id++) {
            for (int i = 0; i < Recipients.MAX_RECIPIENTS; i++) {
                for (int p = 0; p < TextRules.MAX_PARTS; p++) {
                    assertTrue(codes.add(SendTracker.requestCode(id, i, p, false)));
                    assertTrue(codes.add(SendTracker.requestCode(id, i, p, true)));
                }
            }
        }
    }

    @Test
    public void reasonsAndDeliveryStatus() {
        assertEquals("radio_off", SendTracker.reason(2));
        assertEquals("other", SendTracker.reason(99));
        assertEquals("complete", SendTracker.deliveryStatus(0, false));
        assertEquals("pending", SendTracker.deliveryStatus(0x20, false));
        assertEquals("failed", SendTracker.deliveryStatus(0x40, false));
        assertEquals("pending", SendTracker.deliveryStatus(2 << 16, true));
    }

    /**
     * Revision 2: sms.status by client_ref answers not_found with the store
     * and dropped_through, and the client's rule holds - "never accepted"
     * exactly when dropped_through is below hello's next_id (F) of the
     * session it sent over - whatever the phone's clock did.
     */
    @Test
    public void notFoundCarriesDroppedThrough() throws Exception {
        SendStoreTest.Clock clock = new SendStoreTest.Clock();
        File dir = SendStoreTest.dir();
        SendTracker t = tracker(dir, clock, 20);
        String store = t.store().id();
        // session 1: hello (F1), a send that is accepted, one that is refused
        long f1 = t.store().nextId();
        long accepted = (Long) send(t, call("acc"), "acc").get("send_id");
        assertTrue(accepted >= f1);
        Session.Call refused = call("ref");
        t.arrive(refused, "ref");
        assertNull(t.resolve(refused, "ref"));
        refused.fail(new RequestException(ErrorCode.NO_SUBSCRIPTION, "no SIM"));
        RequestException nf = notFound(t, "ref");
        assertEquals(Json.obj("store", store, "dropped_through", 0L), nf.data);
        // never accepted: dropped_through (0) < F1
        assertTrue((Long) nf.data.get("dropped_through") < f1);

        // the phone's clock jumps two days ahead: "acc" is dropped, and says so
        clock.now += 2 * SendStore.KEEP_MS;
        nf = notFound(t, "acc");
        assertEquals(store, nf.data.get("store"));
        assertEquals(accepted, nf.data.get("dropped_through"));
        // so the client may not conclude "never accepted" - for "ref" neither now
        assertTrue((Long) notFound(t, "acc").data.get("dropped_through") >= f1);
        assertTrue((Long) notFound(t, "ref").data.get("dropped_through") >= f1);

        // session 2 after a restart, F2 past the drop: a ref that never came is "never accepted"
        SendTracker u = tracker(dir, clock, 20);
        long f2 = u.store().nextId();
        assertTrue((Long) notFound(u, "never").data.get("dropped_through") < f2);
        assertEquals(Json.obj("store", store, "keep_ms", SendStore.KEEP_MS, "next_id", f2, "dropped_through", accepted),
                Protocol.sends(u.store()));

        // pending still answers pending, not not_found
        Session.Call c = call("p");
        u.arrive(c, "p");
        assertEquals(true, u.statusByRef("p").get("pending"));
        assertNull(u.resolve(c, "p"));
        u.accept(c, "p", 4, ONE, PARTS);
        c.ok(Json.obj());
        assertEquals(f2, u.statusByRef("p").get("send_id"));
    }

    private static RequestException notFound(SendTracker t, String ref) {
        try {
            t.statusByRef(ref);
        } catch (RequestException e) {
            assertEquals(ErrorCode.NOT_FOUND, e.code);
            assertNotNull(e.data);
            return e;
        }
        throw new AssertionError("found: " + ref);
    }
}
