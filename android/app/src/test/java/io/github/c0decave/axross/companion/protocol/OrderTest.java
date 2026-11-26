package io.github.c0decave.axross.companion.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class OrderTest {
    private static Map<String, Object> m(String kind, long id, long date) {
        return Json.obj("kind", kind, "id", id, "date", date);
    }

    @Test
    public void mergeNewestFirstSmsBeforeMms() {
        List<Map<String, Object>> sms = new ArrayList<>(Arrays.asList(m("sms", 9, 5000), m("sms", 8, 3000), m("sms", 7, 3000)));
        List<Map<String, Object>> mms = new ArrayList<>(Arrays.asList(m("mms", 4, 5000), m("mms", 3, 1000)));
        MessageOrder.Page p = MessageOrder.merge(sms, mms, 3);
        assertEquals(Arrays.asList(m("sms", 9, 5000), m("mms", 4, 5000), m("sms", 8, 3000)), p.messages);
        assertEquals("sms", p.next.kind);
        assertEquals(8, p.next.id);
        assertEquals(3000, p.next.date);
        MessageOrder.Page all = MessageOrder.merge(sms, mms, 5);
        assertNull(all.next);
        assertEquals(5, all.messages.size());
    }

    @Test
    public void selectionsAfterAnSms() {
        Requests.Cursor c = new Requests.Cursor(1764150000000L, "sms", 123);
        MessageOrder.Selection s = MessageOrder.smsAfter(c);
        assertEquals("date < ? OR (date = ? AND _id < ?)", s.where);
        assertEquals(Arrays.asList("1764150000000", "1764150000000", "123"), s.args);
        MessageOrder.Selection m = MessageOrder.mmsAfter(c);
        // (MMS in seconds: before 1764150000 s, or in that very second - all MMS of it come after the SMS)
        assertEquals("date <= ? OR date = ?", m.where);
        assertEquals(Arrays.asList("1764149999", "1764150000"), m.args);
    }

    @Test
    public void selectionsAfterAnMms() {
        Requests.Cursor c = new Requests.Cursor(1764150000000L, "mms", 77);
        assertEquals("date < ?", MessageOrder.smsAfter(c).where);
        MessageOrder.Selection m = MessageOrder.mmsAfter(c);
        assertEquals("date <= ? OR (date = ? AND _id < ?)", m.where);
        assertEquals(Arrays.asList("1764149999", "1764150000", "77"), m.args);
    }

    @Test
    public void mmsAfterAMillisecondThatIsNotAWholeSecond() {
        MessageOrder.Selection m = MessageOrder.mmsAfter(new Requests.Cursor(1764150000500L, "sms", 1));
        assertEquals("date <= ?", m.where);
        assertEquals(Arrays.asList("1764150000"), m.args);
        assertEquals("date <= ? OR date = ?", MessageOrder.mmsAfter(new Requests.Cursor(0, "sms", 1)).where);
        assertEquals(Arrays.asList("-1", "0"), MessageOrder.mmsAfter(new Requests.Cursor(0, "sms", 1)).args);
    }

    @Test
    public void and() {
        MessageOrder.Selection s = MessageOrder.smsAfter(null).and("thread_id = ?", "5");
        assertEquals("thread_id = ?", s.where);
        s = MessageOrder.smsAfter(new Requests.Cursor(10, "mms", 1)).and("thread_id = ?", "5");
        assertEquals("(date < ?) AND (thread_id = ?)", s.where);
        assertEquals(Arrays.asList("10", "5"), s.args);
    }

    @Test
    public void sendTracker() {
        SendTracker t = new SendTracker(new RateLimiter.Clock() {
            @Override
            public long nowMs() {
                return 1000;
            }
        });
        long id = t.start(7, Arrays.asList("+1555", "+1666"), Arrays.asList(2, 1));
        assertNull(t.onSent(id, 0, true, -1));
        SendTracker.Event e = t.onSent(id, 0, true, -1);
        assertEquals("sms.sent", e.name);
        assertEquals(7, e.sessionId);
        assertEquals(0L, e.data.get("index"));
        e = t.onSent(id, 1, false, 4);
        assertEquals("sms.failed", e.name);
        assertEquals("no_service", e.data.get("reason"));
        assertNull("a failed recipient reports nothing more", t.onSent(id, 1, true, -1));
        assertNull(t.onDelivered(id, 0, "pending"));
        assertNull(t.onDelivered(id, 0, "complete"));
        e = t.onDelivered(id, 0, "complete");
        assertEquals("sms.delivered", e.name);
        assertEquals("complete", e.data.get("status"));
        assertNull(t.onDelivered(id, 0, "complete"));
        Map<String, Object> status = t.status(id);
        List<?> r = (List<?>) status.get("recipients");
        assertEquals("delivered", ((Map<?, ?>) r.get(0)).get("state"));
        assertEquals("failed", ((Map<?, ?>) r.get(1)).get("state"));
        assertNull(t.status(id + 1));
        assertNull(t.onSent(id + 1, 0, true, -1));
        assertNull(t.onSent(id, 5, true, -1));
    }

    @Test
    public void deliveryStatus() {
        assertEquals("complete", SendTracker.deliveryStatus(0x00, false));
        assertEquals("complete", SendTracker.deliveryStatus(0x02, false));
        assertEquals("pending", SendTracker.deliveryStatus(0x20, false));
        assertEquals("pending", SendTracker.deliveryStatus(0x3f, false));
        assertEquals("failed", SendTracker.deliveryStatus(0x40, false));
        assertEquals("failed", SendTracker.deliveryStatus(0x65, false));
        assertEquals("complete", SendTracker.deliveryStatus(0, true));
        assertEquals("pending", SendTracker.deliveryStatus(2 << 16, true));
        assertEquals("failed", SendTracker.deliveryStatus(3 << 16, true));
    }
}
