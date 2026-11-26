package io.github.c0decave.axross.companion.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/** backup.export's window and backup.ack, on a real session: the session answers while the export waits. */
public class ExportsTest {
    /** An export of {@code batches} backup.items, as Exporter makes it. */
    static final class Exporting implements Session.Handler {
        final Exports exports = new Exports();
        final int batches;
        final CountDownLatch ended = new CountDownLatch(1);

        Exporting(int batches) {
            this.batches = batches;
        }

        @Override
        public Map<String, Object> hello(Session session, Requests.Hello hello) {
            return Json.obj("app", Protocol.APP);
        }

        @Override
        public void handle(Session session, Session.Call call) throws Exception {
            switch (call.method) {
                case "backup.export": {
                    Exports.Export e = exports.start(session, ((Requests.Export) call.value).window);
                    try {
                        call.ok(Json.obj("export_id", e.id, "sms_count", (long) batches, "mms_count", 0L));
                        for (int i = 0; i < batches; i++) {
                            long seq = e.next(session);
                            if (seq < 0) {
                                break;
                            }
                            session.event("backup.items", Json.obj("export_id", e.id, "seq", seq, "items", Arrays.asList()), null, true);
                        }
                        session.event(e.cancelled() ? "backup.failed" : "backup.done", Json.obj("export_id", e.id), null, true);
                    } finally {
                        exports.finish(e);
                        ended.countDown();
                    }
                    return;
                }
                case "backup.ack": {
                    Requests.Ack a = (Requests.Ack) call.value;
                    exports.ack(session, a.exportId, a.seq, () -> call.ok(Json.obj()));
                    return;
                }
                case "backup.cancel":
                    exports.cancel(session, (Long) call.value, () -> call.ok(Json.obj()));
                    return;
                default:
                    call.ok(Json.obj("method", call.method));
            }
        }

        @Override
        public void closed(Session session) {
            exports.closed(session);
        }
    }

    private static Map<String, Object> req(long id, String method, Map<String, Object> params) {
        return Json.obj("id", id, "method", method, "params", params);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Map<String, Object> event) {
        return (Map<String, Object>) event.get("data");
    }

    @Test
    public void aPingIsAnsweredWhileTheExportWaitsForAcks() throws Exception {
        try (Wire w = new Wire()) {
            Exporting h = new Exporting(5);
            w.start(h);
            w.handshake();
            w.send(req(3, "backup.export", Json.obj("window", 2L)));
            assertEquals(3L, w.nextJson().get("id"));
            Map<String, Object> f = w.nextJson();
            assertEquals("backup.items", f.get("event"));
            assertEquals(0L, data(f).get("seq"));
            assertEquals(1L, data(w.nextJson()).get("seq"));
            // the window is full: the next frames are the answers, not seq 2
            Thread.sleep(300);
            w.send(req(4, "ping", Json.obj()));
            f = w.nextJson();
            assertEquals(4L, f.get("id"));
            assertTrue(((Map<?, ?>) f.get("result")).containsKey("time"));
            w.send(req(5, "threads.list", Json.obj()));
            assertEquals(5L, w.nextJson().get("id"));
            // one ack: one more event
            w.send(req(6, "backup.ack", Json.obj("export_id", 1L, "seq", 0L)));
            f = w.nextJson();
            assertEquals(6L, f.get("id"));
            assertEquals(Json.obj(), f.get("result"));
            assertEquals(2L, data(w.nextJson()).get("seq"));
            w.send(req(7, "ping", Json.obj()));
            assertEquals(7L, w.nextJson().get("id"));
            // an ack of everything sent: the rest, and backup.done (not counted)
            w.send(req(8, "backup.ack", Json.obj("export_id", 1L, "seq", 2L)));
            assertEquals(8L, w.nextJson().get("id"));
            assertEquals(3L, data(w.nextJson()).get("seq"));
            assertEquals(4L, data(w.nextJson()).get("seq"));
            assertEquals("backup.done", w.nextJson().get("event"));
            assertTrue(h.ended.await(5, TimeUnit.SECONDS));
            // after the end its acks are still answered; above the last seq: bad_request
            w.send(req(9, "backup.ack", Json.obj("export_id", 1L, "seq", 4L)));
            assertEquals(true, w.nextJson().get("ok"));
            w.send(req(10, "backup.ack", Json.obj("export_id", 1L, "seq", 5L)));
            f = w.nextJson();
            assertEquals("bad_request", ((Map<?, ?>) f.get("error")).get("code"));
            assertEquals("seq", ((Map<?, ?>) ((Map<?, ?>) f.get("error")).get("data")).get("field"));
            w.send(req(11, "backup.ack", Json.obj("export_id", 2L, "seq", 0L)));
            assertEquals("not_found", ((Map<?, ?>) w.nextJson().get("error")).get("code"));
        }
    }

    @Test
    public void withoutAWindowNothingWaits() throws Exception {
        try (Wire w = new Wire()) {
            w.start(new Exporting(70));
            w.handshake();
            w.send(req(3, "backup.export", Json.obj()));
            assertEquals(3L, w.nextJson().get("id"));
            for (long seq = 0; seq < 70; seq++) {
                assertEquals(seq, data(w.nextJson()).get("seq"));
            }
            assertEquals("backup.done", w.nextJson().get("event"));
        }
    }

    @Test
    public void aCancelEndsAWaitingExport() throws Exception {
        try (Wire w = new Wire()) {
            Exporting h = new Exporting(5);
            w.start(h);
            w.handshake();
            w.send(req(3, "backup.export", Json.obj("window", 1L)));
            assertEquals(3L, w.nextJson().get("id"));
            assertEquals(0L, data(w.nextJson()).get("seq"));
            // a second export while one runs (and waits): busy
            w.send(req(4, "backup.export", Json.obj("window", 1L)));
            assertEquals("busy", ((Map<?, ?>) w.nextJson().get("error")).get("code"));
            w.send(req(5, "backup.cancel", Json.obj("export_id", 1L)));
            assertEquals(5L, w.nextJson().get("id"));
            assertEquals("backup.failed", w.nextJson().get("event"));
            assertTrue(h.ended.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void aClosedSessionEndsAWaitingExport() throws Exception {
        Exporting h = new Exporting(5);
        try (Wire w = new Wire()) {
            w.start(h);
            w.handshake();
            w.send(req(3, "backup.export", Json.obj("window", 1L)));
            assertEquals(3L, w.nextJson().get("id"));
            assertEquals(0L, data(w.nextJson()).get("seq"));
        }
        assertTrue("the export stopped waiting", h.ended.await(5, TimeUnit.SECONDS));
    }
}
