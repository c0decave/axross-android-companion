package io.github.c0decave.axross.companion.protocol;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What became of each send (docs/PROTOCOL.md, "sms.send" and
 * "sms.status"), over the {@link SendStore} on disk: the sends' results and
 * states, the rate limit, and - in memory - the client_refs of sends that
 * are still being checked and the sessions that get a send's events.
 *
 * <p>The order a send with a client_ref goes through:
 * <ol>
 *   <li>{@link #arrive}, on the session's reader thread as soon as the
 *     request is parsed: the ref is "checking" (sms.status says
 *     {@code "pending": true}) unless it is in the store already;</li>
 *   <li>{@link #resolve}: a ref in the store is answered as a duplicate; a
 *     ref another request is checking waits for that one's outcome and
 *     then answers the duplicate, or the same refusal;</li>
 *   <li>the checks (permission, SIM, the text's parts), then
 *     {@link #accept}: the rate limit, and the send written to the store
 *     before any part is handed to Android;</li>
 *   <li>a refusal anywhere ends the registration when the error is answered
 *     (the Call's {@code whenAnswered}), so that a later "not_found" means
 *     "never accepted, not sent".</li>
 * </ol>
 */
public final class SendTracker {
    /** How long a second request with the same client_ref waits for the first's outcome. */
    public static final long WAIT_MS = 60_000L;
    /** The sessions of this many sends are remembered (for their events). */
    static final int MAX_ROUTES = 5000;

    /** Android's SmsManager results this protocol names (the rest is "other"). */
    public static String reason(int resultCode) {
        switch (resultCode) {
            case 1: return "generic_failure";
            case 2: return "radio_off";
            case 3: return "null_pdu";
            case 4: return "no_service";
            case 5: return "limit_exceeded";
            case 6: return "fdn_check_failure";
            case 7: return "short_code_not_allowed";
            case 8: return "short_code_never_allowed";
            default: return "other";
        }
    }

    /**
     * A delivery report's status (SmsMessage.getStatus()) as "complete",
     * "pending" or "failed". GSM/UMTS/LTE (3GPP): the TP-Status, 0x00-0x1f
     * done, 0x20-0x3f still trying, from 0x40 failed. CDMA (3GPP2): the
     * error class in bits 16+ (0 none, 2 temporary, 3 permanent).
     */
    public static String deliveryStatus(int status, boolean cdma) {
        if (cdma) {
            int errorClass = (status >> 16) & 0xff;
            return errorClass == 0 ? "complete" : errorClass == 2 ? "pending" : "failed";
        }
        if (status >= 0x40) {
            return "failed";
        }
        return status >= 0x20 ? "pending" : "complete";
    }

    /**
     * A PendingIntent's request code for (send, recipient, part, sent or
     * delivered): different for every one of some seven million sends in a
     * row (2^32 / 600), as send ids are consecutive.
     */
    public static int requestCode(long sendId, int index, int part, boolean delivery) {
        long perSend = 2L * Recipients.MAX_RECIPIENTS * TextRules.MAX_PARTS;
        return (int) (sendId * perSend + (index * (long) TextRules.MAX_PARTS + part) * 2 + (delivery ? 1 : 0));
    }

    /** A request's hold on its client_ref while it is checked. */
    static final class Claim {
        final String ref;
        /** This request checks the ref (others wait for it). */
        boolean owner;
        /** The request that checked the ref when this one arrived (this one waits for it). */
        Claim first;
        /** The owner's send is in the store. */
        boolean accepted;
        /** The owner's answer is out: those waiting for it go on. */
        boolean done;
        /** Why it was not accepted (when done and not accepted). */
        RequestException refusal;

        Claim(String ref) {
            this.ref = ref;
        }
    }

    /** An event that is due, and for which session (-1: none of this process). */
    public static final class Event {
        public final long sessionId;
        public final String name;
        public final Map<String, Object> data;

        Event(long sessionId, String name, Map<String, Object> data) {
            this.sessionId = sessionId;
            this.name = name;
            this.data = data;
        }
    }

    private final SendStore store;
    private final RateLimiter limiter;
    /** client_ref -> the request checking it. */
    private final Map<String, Claim> checking = new HashMap<>();
    /** send id -> the session it came from (this process only). */
    private final LinkedHashMap<Long, Long> routes = new LinkedHashMap<>();

    /** The limiter learns the store's sends of the last hour: the limit holds across service runs. */
    public SendTracker(SendStore store, RateLimiter limiter) {
        this.store = store;
        this.limiter = limiter;
        for (long[] s : store.acceptedWithin(3_600_000L)) {
            limiter.record(s[0], (int) s[1]);
        }
    }

    public SendStore store() {
        return store;
    }

    /**
     * sms.send has been parsed (the reader thread, before the request is
     * queued): its client_ref is "checking" from now on, unless the store
     * has it or another request checks it.
     */
    public void arrive(Session.Call call, String clientRef) {
        if (clientRef == null) {
            return;
        }
        Claim c = attach(call, clientRef);
        synchronized (this) {
            if (store.byRef(clientRef) == null) {
                Claim first = checking.get(clientRef);
                if (first == null) {
                    checking.put(clientRef, c);
                    c.owner = true;
                } else {
                    c.first = first;
                }
            }
        }
    }

    private Claim attach(final Session.Call call, String clientRef) {
        final Claim c = new Claim(clientRef);
        call.attach(c);
        call.whenAnswered(new Runnable() {
            @Override
            public void run() {
                settle(c, call.failure());
            }
        });
        return c;
    }

    private static Claim claimOf(Session.Call call, String clientRef) {
        Object a = call.attachment();
        return a instanceof Claim && ((Claim) a).ref.equals(clientRef) ? (Claim) a : null;
    }

    /**
     * Before the checks: the earlier send's result ("duplicate": true) when
     * the store has the client_ref - after waiting for another request that
     * still checks it - or null when this request is to check and send.
     *
     * @throws RequestException the other request's refusal, the same
     */
    public Map<String, Object> resolve(Session.Call call, String clientRef) throws RequestException {
        if (clientRef == null) {
            return null;
        }
        Claim mine = claimOf(call, clientRef);
        if (mine == null) {
            mine = attach(call, clientRef);
        }
        long deadline = System.currentTimeMillis() + WAIT_MS;
        synchronized (this) {
            while (true) {
                if (mine.owner) {
                    return null;
                }
                Claim first = mine.first;
                mine.first = null;
                if (first == null) {
                    SendStore.Send s = store.byRef(clientRef);
                    if (s != null) {
                        return result(s, true);
                    }
                    first = checking.get(clientRef);
                    if (first == null) {
                        checking.put(clientRef, mine);
                        mine.owner = true;
                        return null;
                    }
                }
                while (!first.done) {
                    long left = deadline - System.currentTimeMillis();
                    if (left <= 0) {
                        throw new RequestException(ErrorCode.INTERNAL, "the first request with this client_ref did not end");
                    }
                    try {
                        wait(left);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RequestException(ErrorCode.INTERNAL, "interrupted");
                    }
                }
                if (first.refusal != null) {
                    RequestException r = first.refusal;
                    throw new RequestException(r.code, r.getMessage(), r.data == null ? null : new LinkedHashMap<>(r.data));
                }
                // (accepted: the store has it now)
            }
        }
    }

    /**
     * The checks have passed: the rate limit, then the send written to the
     * store. Returns sms.send's result ("duplicate": false); send it.
     *
     * @throws RequestException rate_limited; internal when the store
     *     cannot be written (then nothing may be sent)
     */
    public Map<String, Object> accept(Session.Call call, String clientRef, long sessionId, List<String> addresses,
            List<Integer> parts) throws RequestException {
        Claim mine = clientRef == null ? null : claimOf(call, clientRef);
        if (clientRef != null && (mine == null || !mine.owner)) {
            throw new IllegalStateException("accept without resolve");
        }
        synchronized (this) {
            long wait = limiter.tryAcquire(addresses.size());
            if (wait > 0) {
                throw new RequestException(ErrorCode.RATE_LIMITED, "too many messages sent in a short time",
                        Json.obj("retry_after_ms", wait));
            }
            SendStore.Send s;
            try {
                s = store.add(clientRef, addresses, parts);
            } catch (IOException e) {
                throw new RequestException(ErrorCode.INTERNAL, "the send could not be recorded: not sent");
            }
            routes.put(s.id, sessionId);
            for (Iterator<Long> it = routes.keySet().iterator(); routes.size() > MAX_ROUTES; ) {
                it.next();
                it.remove();
            }
            if (mine != null) {
                // (those waiting go on when this answer is out: theirs come after it)
                mine.accepted = true;
                checking.remove(clientRef);
            }
            return result(s, false);
        }
    }

    /** The request was answered: a claim not accepted by then was refused (with the answer's error). */
    synchronized void settle(Claim c, RequestException failure) {
        if (!c.owner || c.done) {
            return;
        }
        c.done = true;
        if (!c.accepted) {
            c.refusal = failure != null ? failure : new RequestException(ErrorCode.INTERNAL, "not accepted");
        }
        if (checking.get(c.ref) == c) {
            checking.remove(c.ref);
        }
        notifyAll();
    }

    /** True for a result of {@link #resolve} or {@link #accept} that is a duplicate (nothing to send). */
    public static boolean isDuplicate(Map<String, Object> result) {
        return Boolean.TRUE.equals(result.get("duplicate"));
    }

    /** sms.send's result for a recorded send. */
    private static Map<String, Object> result(SendStore.Send s, boolean duplicate) {
        List<Object> list = new ArrayList<>();
        for (int i = 0; i < s.size(); i++) {
            SendStore.Recipient r = s.recipient(i);
            list.add(Json.obj("index", (long) i, "address", r.address, "parts", (long) r.parts));
        }
        Map<String, Object> out = Json.obj("send_id", s.id, "recipients", list);
        if (s.clientRef != null) {
            out.put("client_ref", s.clientRef);
        }
        out.put("duplicate", duplicate);
        return out;
    }

    // -- Android's reports ------------------------------------------------------------------

    /**
     * A part's "sent" report, from the PendingIntent of store {@code storeId}
     * (one of another store - the app's data were cleared since - is
     * ignored). Returns the event that is due, or null.
     */
    public Event onSent(String storeId, long sendId, int index, int part, boolean ok, int resultCode) {
        if (!store.id().equals(storeId)) {
            return null;
        }
        return route(store.sent(sendId, index, part, ok, resultCode));
    }

    /** A part's delivery report ({@code status}: "complete", "pending", "failed"). */
    public Event onDelivered(String storeId, long sendId, int index, int part, String status) {
        if (!store.id().equals(storeId)) {
            return null;
        }
        return route(store.delivered(sendId, index, part, status));
    }

    private synchronized Event route(SendStore.Event e) {
        if (e == null) {
            return null;
        }
        Long session = routes.get(e.send.id);
        return new Event(session == null ? -1 : session, e.name, e.data);
    }

    // -- sms.status -------------------------------------------------------------------------

    /** sms.status by send_id, or null (not_found). */
    public Map<String, Object> status(long sendId) {
        SendStore.Send s = store.byId(sendId);
        return s == null ? null : status(s);
    }

    /**
     * sms.status by client_ref: the send; {@code "pending": true} while a
     * request with it is being checked; null (not_found) when the store has
     * no send with it - it was never accepted.
     */
    public synchronized Map<String, Object> status(String clientRef) {
        SendStore.Send s = store.byRef(clientRef);
        if (s != null) {
            return status(s);
        }
        if (checking.containsKey(clientRef)) {
            return Json.obj("client_ref", clientRef, "send_id", null, "recipients", new ArrayList<Object>(),
                    "pending", true, "store", store.id());
        }
        return null;
    }

    private Map<String, Object> status(SendStore.Send s) {
        List<Object> list = new ArrayList<>();
        for (int i = 0; i < s.size(); i++) {
            list.add(Json.obj("index", (long) i, "address", s.recipient(i).address,
                    "state", store.state(s, i), "reason", store.reason(s, i)));
        }
        Map<String, Object> out = Json.obj("send_id", s.id, "recipients", list);
        if (s.clientRef != null) {
            out.put("client_ref", s.clientRef);
        }
        out.put("store", store.id());
        out.put("accepted", s.accepted);
        return out;
    }
}
