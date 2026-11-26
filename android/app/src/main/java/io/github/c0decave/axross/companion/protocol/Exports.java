package io.github.c0decave.axross.companion.protocol;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The running exports (docs/PROTOCOL.md, "backup.export"): one at a time per
 * session, their cancellation and - with {@code window} - their flow
 * control: at most {@code window} backup.items events sent and not yet
 * acknowledged by backup.ack. An export that waits for acks waits on its
 * own thread; the session goes on reading and answering.
 *
 * <p>A session's export is kept after it ended, until the session's next
 * export or its end, so that acks of its last events (which may come after
 * backup.done) are answered {@code {}}.
 */
public final class Exports {
    public static final int MAX_WINDOW = 64;

    /** One export. */
    public static final class Export {
        public final long id;
        final long session;
        /** 0: no flow control. */
        public final int window;
        private long sent = -1;
        private long acked = -1;
        private boolean cancelled;
        private boolean running = true;

        Export(long id, long session, int window) {
            this.id = id;
            this.session = session;
            this.window = window;
        }

        public synchronized boolean cancelled() {
            return cancelled;
        }

        /**
         * Before the next backup.items event: waits while {@code window}
         * events are unacknowledged. Then marks that event as sent (before
         * it goes out: its ack may come at once).
         *
         * @return its seq, or -1 when the export is to stop (cancelled, the
         *     session closed)
         */
        public synchronized long next(Session session) {
            while (!cancelled && session.isOpen() && window > 0 && sent + 1 - acked > window) {
                try {
                    wait(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return -1;
                }
            }
            if (cancelled || !session.isOpen()) {
                return -1;
            }
            return ++sent;
        }

        /** The last seq sent (-1: none yet) and the last acknowledged. */
        public synchronized long sent() {
            return sent;
        }

        public synchronized long acked() {
            return acked;
        }

        synchronized void cancel() {
            cancelled = true;
            notifyAll();
        }

        synchronized void ack(long seq) {
            if (seq > acked) {
                acked = seq;
                notifyAll();
            }
        }

        synchronized void wake() {
            notifyAll();
        }
    }

    private final AtomicLong ids = new AtomicLong();
    /** Session number -> its running or last export. */
    private final Map<Long, Export> bySession = new HashMap<>();

    /** A new export for the session; busy while one runs. {@code window} 0: none. */
    public synchronized Export start(Session session, int window) throws RequestException {
        Export last = bySession.get(session.number);
        if (last != null && last.running) {
            throw new RequestException(ErrorCode.BUSY, "an export is running in this session");
        }
        Export e = new Export(ids.incrementAndGet(), session.number, window);
        bySession.put(session.number, e);
        return e;
    }

    /** The export has sent backup.done or backup.failed (or stopped). */
    public synchronized void finish(Export e) {
        e.running = false;
        e.wake();
    }

    /**
     * backup.cancel: not_found unless it is the session's running export.
     * {@code answer} (the {} reply) runs first: it comes before the export's
     * backup.failed.
     */
    public void cancel(Session session, long exportId, Runnable answer) throws RequestException {
        Export e;
        synchronized (this) {
            e = bySession.get(session.number);
            if (e == null || e.id != exportId || !e.running) {
                throw new RequestException(ErrorCode.NOT_FOUND, "no such export");
            }
        }
        answer.run();
        e.cancel();
    }

    /**
     * backup.ack: every backup.items up to {@code seq} is acknowledged.
     * {@code answer} (the {} reply) runs before the export may go on, so
     * that the reply comes before the next events.
     *
     * @throws RequestException not_found (not the session's export),
     *     bad_request "seq" (above the last seq sent)
     */
    public void ack(Session session, long exportId, long seq, Runnable answer) throws RequestException {
        Export e;
        synchronized (this) {
            e = bySession.get(session.number);
        }
        if (e == null || e.id != exportId) {
            throw new RequestException(ErrorCode.NOT_FOUND, "no such export");
        }
        if (seq > e.sent()) {
            throw RequestException.bad("seq", "seq is above the last backup.items sent");
        }
        answer.run();
        e.ack(seq);
    }

    /** The session has ended: its export stops waiting. */
    public void closed(Session session) {
        Export e;
        synchronized (this) {
            e = bySession.remove(session.number);
        }
        if (e != null) {
            e.wake();
        }
    }
}
