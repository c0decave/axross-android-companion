package io.github.c0decave.axross.companion.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/** sms.send's client_ref: a send asked for again is answered, not sent again. */
public class SendRefsTest {
    private static final class Clock implements RateLimiter.Clock {
        long now = 1_000_000;

        @Override
        public long nowMs() {
            return now;
        }
    }

    @Test
    public void aClientRefIsSentOnce() throws Exception {
        Clock clock = new Clock();
        SendTracker t = new SendTracker(clock);
        RateLimiter limiter = new RateLimiter(2, 3, clock);
        List<String> one = Arrays.asList("+15555215554");
        List<Integer> parts = Arrays.asList(2);

        Map<String, Object> first = t.accept(4, "ref-1", one, parts, limiter);
        assertFalse(SendTracker.isDuplicate(first));
        assertEquals(1L, first.get("send_id"));
        assertEquals("ref-1", first.get("client_ref"));
        assertEquals(false, first.get("duplicate"));
        assertEquals(Arrays.asList(Json.obj("index", 0L, "address", "+15555215554", "parts", 2L)), first.get("recipients"));

        // the same ref again (from another session): the first's result, not counted
        for (int i = 0; i < 5; i++) {
            Map<String, Object> again = t.accept(5, "ref-1", one, parts, limiter);
            assertTrue(SendTracker.isDuplicate(again));
            assertEquals(1L, again.get("send_id"));
            assertEquals("ref-1", again.get("client_ref"));
            assertEquals(first.get("recipients"), again.get("recipients"));
        }
        // the limit (2 a minute) still has room for one: the duplicates took none
        Map<String, Object> second = t.accept(4, "ref-2", one, parts, limiter);
        assertEquals(2L, second.get("send_id"));
        try {
            t.accept(4, "ref-3", one, parts, limiter);
            throw new AssertionError("over the limit");
        } catch (RequestException e) {
            assertEquals(ErrorCode.RATE_LIMITED, e.code);
        }
        // a refused send's ref was not accepted: it may come again
        assertNull(t.duplicate("ref-3"));
        // without a ref: never a duplicate, no client_ref in the result
        clock.now += 61_000;
        Map<String, Object> plain = t.accept(4, null, one, parts, limiter);
        assertFalse(SendTracker.isDuplicate(plain));
        assertFalse(plain.containsKey("client_ref"));
        assertNull(t.duplicate(null));
        // the events of the first still go to its session
        assertNull(t.onSent(1, 0, true, -1));
        assertEquals(4L, t.onSent(1, 0, true, -1).sessionId);
    }

    @Test
    public void statusByClientRef() throws Exception {
        Clock clock = new Clock();
        SendTracker t = new SendTracker(clock);
        RateLimiter limiter = new RateLimiter(20, 100, clock);
        t.accept(4, "abc_DEF-9", Arrays.asList("+1555", "+1666"), Arrays.asList(1, 1), limiter);
        t.onSent(1, 1, false, 2);
        Map<String, Object> st = t.status("abc_DEF-9");
        assertEquals(1L, st.get("send_id"));
        assertEquals("abc_DEF-9", st.get("client_ref"));
        List<?> r = (List<?>) st.get("recipients");
        assertEquals("sending", ((Map<?, ?>) r.get(0)).get("state"));
        assertEquals("failed", ((Map<?, ?>) r.get(1)).get("state"));
        assertEquals("radio_off", ((Map<?, ?>) r.get(1)).get("reason"));
        assertEquals("abc_DEF-9", t.status(1).get("client_ref"));
        assertNull(t.status("other"));

        // an hour later: gone, and the ref may be used again
        clock.now += SendTracker.KEEP_MS + 1;
        assertNull(t.status("abc_DEF-9"));
        assertNull(t.duplicate("abc_DEF-9"));
        Map<String, Object> fresh = t.accept(4, "abc_DEF-9", Arrays.asList("+1555"), Arrays.asList(1), limiter);
        assertFalse(SendTracker.isDuplicate(fresh));
        assertEquals(2L, fresh.get("send_id"));
    }
}
